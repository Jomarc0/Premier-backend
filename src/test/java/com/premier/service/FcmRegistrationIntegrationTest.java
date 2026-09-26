package com.premier.service;

import com.premier.model.*;
import com.premier.repository.*;
import com.premier.request.FcmTokenRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class FcmRegistrationIntegrationTest {
    @Autowired FirebaseService firebase;
    @Autowired PassengerRepository passengers;
    @Autowired PassengerFcmTokenRepository tokens;
    @Autowired FarePaymentService fares;
    @Autowired TransactionRepository ledger;
    @Autowired com.premier.payment.repository.PaymentNotificationRepository notices;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.premier.device.repository.DeviceRepository devices;
    @Autowired com.premier.driver.repository.VehicleRepository vehicles;
    @Autowired com.premier.driver.repository.DriverRepository drivers;
    @Autowired com.premier.driver.repository.DriverShiftRepository shifts;
    @Autowired com.premier.trip.repository.VehicleTripRepository trips;

    private Passenger passenger(String token) {
        return passengers.saveAndFlush(Passenger.builder().cardNumber(UUID.randomUUID().toString())
                .balance(new BigDecimal("200.00")).status(PassengerStatus.ACTIVE).is2FaEnabled(true).fcmToken(token).build());
    }
    private FcmTokenRequest request(String token) {
        var request = new FcmTokenRequest(); request.setFcmToken(token); return request;
    }
    @Test void accountSwitchClearsLegacyOwnerAndOldLogoutCannotDeleteNewOwner() {
        String token = "synthetic-" + UUID.randomUUID();
        var old = passenger(token); var current = passenger(null);
        firebase.updateFcmToken(old, request(token));
        firebase.updateFcmToken(current, request(token));
        firebase.removeFcmToken(old, request(token));
        assertThat(passengers.findById(old.getId()).orElseThrow().getFcmToken()).isNull();
        assertThat(tokens.findByFcmToken(token).orElseThrow().getPassenger().getId()).isEqualTo(current.getId());
        assertThat(passengers.findById(old.getId()).orElseThrow().getBalance()).isEqualByComparingTo("200.00");
        assertThat(passengers.findById(current.getId()).orElseThrow().getBalance()).isEqualByComparingTo("200.00");
    }
    @Test void refreshAndLogoutPreserveOtherDevicesAndResyncUpdatesTimestamp() {
        var owner = passenger(null);
        String one = "synthetic-" + UUID.randomUUID(), two = "synthetic-" + UUID.randomUUID();
        firebase.updateFcmToken(owner, request(one));
        var first = tokens.findByFcmToken(one).orElseThrow().getUpdatedAt();
        firebase.updateFcmToken(owner, request(one));
        firebase.updateFcmToken(owner, request(two));
        assertThat(tokens.findByFcmToken(one).orElseThrow().getUpdatedAt()).isAfterOrEqualTo(first);
        assertThat(tokens.findByPassengerId(owner.getId())).hasSize(2);
        firebase.removeFcmToken(owner, request(one));
        assertThat(tokens.findByFcmToken(one)).isEmpty();
        assertThat(tokens.findByFcmToken(two)).isPresent();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void committedFareReachesSenderAndProviderOutageCannotUndoMoney(boolean providerFails) throws Exception {
        var activeTrip = TripTestFixture.activeTrip("TEST-01", vehicles, drivers, shifts, trips);
        var owner = passenger(null);
        String destination = "synthetic-" + UUID.randomUUID();
        firebase.updateFcmToken(owner, request(destination));
        var terminal = com.premier.device.security.DevicePrincipal.from(devices.saveAndFlush(
                com.premier.device.model.Device.builder().deviceId(UUID.randomUUID().toString())
                    .deviceName("isolated notification test terminal")
                    .deviceType(com.premier.device.model.DeviceType.VEHICLE_TERMINAL)
                    .vehicleId(activeTrip.getVehicle().getId()).plateNumber("TEST-01").tokenHash("synthetic-not-a-credential").build()));
        var fareRequest = new com.premier.rfid.DeviceFareRequest();
        fareRequest.setPayload(fares.generateQrToken(owner).getData().getPayload());
        fareRequest.setIdempotencyKey(UUID.randomUUID().toString()); fareRequest.setPlateNumber("TEST-01");
        fareRequest.setRequestNonce(UUID.randomUUID().toString()); fareRequest.setRequestTimestamp(java.time.Instant.now().toString());
        var payment = fares.processQrPayment(fareRequest, terminal).getData();
        var intent = notices.findAll().stream().filter(n -> n.getReference().equals(payment.getReferenceNumber())).findFirst().orElseThrow();
        intent.setDueAt(java.time.Instant.now().minusSeconds(3600)); notices.saveAndFlush(intent);
        var sender = org.mockito.Mockito.mock(com.premier.payment.service.PaymentPushSender.class);
        org.mockito.Mockito.when(sender.sendPayment(org.mockito.ArgumentMatchers.eq(owner.getId()), org.mockito.ArgumentMatchers.eq(destination), org.mockito.ArgumentMatchers.eq("FARE"),
                org.mockito.ArgumentMatchers.eq(payment.getReferenceNumber()), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> {
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(ledger.findByReferenceNumberAndPassengerId(payment.getReferenceNumber(), owner.getId())).isPresent();
                    if (providerFails) throw new java.util.concurrent.TimeoutException();
                    return "projects/synthetic/messages/accepted";
                });
        var worker = new com.premier.payment.service.PaymentNotificationService(notices, passengers, tokens, ledger, transactions, sender);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "enabled", true);
        worker.deliverPending();
        org.mockito.Mockito.verify(sender).sendPayment(org.mockito.ArgumentMatchers.eq(owner.getId()), org.mockito.ArgumentMatchers.eq(destination), org.mockito.ArgumentMatchers.eq("FARE"),
                org.mockito.ArgumentMatchers.eq(payment.getReferenceNumber()), org.mockito.ArgumentMatchers.anyLong());
        var stored = notices.findById(intent.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(providerFails ? "PENDING" : "DELIVERED");
        assertThat(passengers.findById(owner.getId()).orElseThrow().getBalance()).isEqualByComparingTo("140.00");
        System.out.printf("FCM_SYNTHETIC_DB_EVIDENCE passenger=%d notification=%d kind=%s status=%s attempts=%d wallet=140.00 providerMocked=true%n",
                owner.getId(), stored.getId(), stored.getKind(), stored.getStatus(), stored.getAttempts());
    }
}
