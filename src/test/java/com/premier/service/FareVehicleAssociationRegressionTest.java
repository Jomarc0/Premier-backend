package com.premier.service;

import com.premier.device.model.Device;
import com.premier.device.model.DeviceType;
import com.premier.device.repository.DeviceRepository;
import com.premier.device.security.DevicePrincipal;
import com.premier.driver.model.Vehicle;
import com.premier.driver.repository.VehicleRepository;
import com.premier.model.Passenger;
import com.premier.model.PassengerStatus;
import com.premier.model.Transaction;
import com.premier.model.TransactionStatus;
import com.premier.model.TransactionType;
import com.premier.repository.PassengerRepository;
import com.premier.repository.TransactionRepository;
import com.premier.response.TransactionResponse;
import com.premier.rfid.DeviceFareRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FareVehicleAssociationRegressionTest {

    @Autowired FarePaymentService fares;
    @Autowired PassengerRepository passengers;
    @Autowired TransactionRepository transactions;
    @Autowired VehicleRepository vehicles;
    @Autowired DeviceRepository devices;
    @Autowired com.premier.admin.service.AdminService adminService;
    @Autowired com.premier.driver.repository.DriverRepository drivers;
    @Autowired com.premier.driver.repository.DriverAssignmentRepository assignments;

    @Test
    void rfidFareStoresAuthenticatedVehicleAndPlateSnapshot() {
        Fixture fixture = fixture("DAR-5315");
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        fixture.passenger().setRfidUid(uid);
        passengers.saveAndFlush(fixture.passenger());
        DeviceFareRequest request = request(fixture.plate());
        request.setRfidUid(uid);

        var payment = fares.processRfidPayment(request, fixture.device()).getData();

        assertFare(payment.getReferenceNumber(), fixture);
    }

    @Test
    void nfcFareStoresAuthenticatedVehicleAndPlateSnapshot() {
        Fixture fixture = fixture("DAR-5315");
        DeviceFareRequest request = request(fixture.plate());
        request.setMobileNfcToken(fares.generateMobileNfcToken(fixture.passenger()).getData().getPayload());

        var payment = fares.processMobileNfcTokenPayment(request, fixture.device()).getData();

        assertFare(payment.getReferenceNumber(), fixture);
    }

    @Test
    void qrFareUsesTheVehicleThatProcessedItAndAdminPaginationReturnsThePlate() {
        Fixture fixture = fixture("DAR-5315");
        DeviceFareRequest request = request(fixture.plate());
        request.setPayload(fares.generateQrToken(fixture.passenger()).getData().getPayload());

        var payment = fares.processQrPayment(request, fixture.device()).getData();
        assertFare(payment.getReferenceNumber(), fixture);

        var page = adminService.getAllTransactions(0, 100).getData();
        assertThat(page.getTotalElements()).isPositive();
        TransactionResponse row = page.getContent().stream()
                .filter(item -> payment.getReferenceNumber().equals(item.getReferenceNumber()))
                .findFirst().orElseThrow();
        assertThat(row.getPlateNumber()).isEqualTo(fixture.plate());
        assertThat(row.getVehicleId()).isEqualTo(fixture.vehicle().getId());
        assertThat(row.getPassengerId()).isEqualTo(fixture.passenger().getId());
    }

    @Test
    void fareFromAnotherVehicleUsesThatVehiclesPlate() {
        Fixture fixture = fixture("ALT-2048");
        DeviceFareRequest request = request(fixture.plate());
        request.setPayload(fares.generateQrToken(fixture.passenger()).getData().getPayload());

        var payment = fares.processQrPayment(request, fixture.device()).getData();

        assertFare(payment.getReferenceNumber(), fixture);
    }

    @Test
    void inactiveAssignedDriverDoesNotBlockQrPayment() {
        Fixture fixture = fixture("DRV-0001");
        var driver = drivers.saveAndFlush(com.premier.driver.model.Driver.builder()
                .fullName("Peter Santos").licenseNumber("DL-" + UUID.randomUUID())
                .phoneNumber("09000000000").status(com.premier.driver.model.DriverStatus.ACTIVE).build());
        assignments.saveAndFlush(com.premier.driver.model.DriverAssignment.builder()
                .driver(driver).vehicle(fixture.vehicle())
                .status(com.premier.driver.model.AssignmentStatus.ACTIVE).build());
        driver.setStatus(com.premier.driver.model.DriverStatus.INACTIVE);
        drivers.saveAndFlush(driver);

        DeviceFareRequest request = request(fixture.plate());
        request.setPayload(fares.generateQrToken(fixture.passenger()).getData().getPayload());

        var payment = fares.processQrPayment(request, fixture.device()).getData();

        assertFare(payment.getReferenceNumber(), fixture);
    }

    @Test
    void nonFareAndUnassociatedHistoricalFareExposeNoBusPlate() {
        Transaction topUp = Transaction.builder()
                .type(TransactionType.TOPUP).status(TransactionStatus.SUCCESS)
                .amount(new BigDecimal("500.00")).vehiclePlateNumber("SHOULD-NOT-SHOW").build();
        Transaction historicalFare = Transaction.builder()
                .type(TransactionType.FARE_DEDUCTION).status(TransactionStatus.SUCCESS)
                .amount(new BigDecimal("60.00")).build();

        assertThat(TransactionResponse.from(topUp).getPlateNumber()).isNull();
        assertThat(TransactionResponse.from(topUp).getVehicleId()).isNull();
        assertThat(TransactionResponse.from(historicalFare).getPlateNumber()).isNull();
    }

    private void assertFare(String reference, Fixture fixture) {
        Transaction transaction = transactions.findByReferenceNumberAndPassengerId(
                reference, fixture.passenger().getId()).orElseThrow();
        assertThat(transaction.getPaymentMethod()).isNotNull();
        assertThat(transaction.getVehicle().getId()).isEqualTo(fixture.vehicle().getId());
        assertThat(transaction.getVehiclePlateNumber()).isEqualTo(fixture.plate());
        assertThat(transaction.getDriverShift()).isNull();
        assertThat(transaction.getTrip()).isNull();
        assertThat(transaction.getTripDirection()).isEqualTo(com.premier.trip.model.TripDirection.SM_TO_GRAND);
        TransactionResponse response = TransactionResponse.from(transaction);
        assertThat(response.getPlateNumber()).isEqualTo(fixture.plate());
        assertThat(response.getVehicleId()).isEqualTo(fixture.vehicle().getId());
    }

    private Fixture fixture(String plate) {
        String suffix = UUID.randomUUID().toString();
        Vehicle vehicle = vehicles.saveAndFlush(Vehicle.builder()
                .plateNumber(plate)
                .route("SM Terminal to Grand Terminal")
                .status(com.premier.driver.model.VehicleStatus.ACTIVE)
                .totalCapacity(50).build());
        Passenger passenger = passengers.saveAndFlush(Passenger.builder()
                .cardNumber("card-" + suffix).rfidUid("uid-" + suffix)
                .status(PassengerStatus.ACTIVE).is2FaEnabled(true)
                .balance(new BigDecimal("1000.00")).build());
        DevicePrincipal device = DevicePrincipal.from(devices.saveAndFlush(Device.builder()
                .deviceId("terminal-" + suffix).deviceName("Vehicle terminal")
                .deviceType(DeviceType.VEHICLE_TERMINAL).vehicleId(vehicle.getId())
                .plateNumber(vehicle.getPlateNumber()).tokenHash("test-only").build()));
        return new Fixture(vehicle.getPlateNumber(), vehicle, passenger, device);
    }

    private DeviceFareRequest request(String plate) {
        DeviceFareRequest request = new DeviceFareRequest();
        request.setIdempotencyKey(UUID.randomUUID().toString());
        request.setRequestNonce(UUID.randomUUID().toString());
        request.setRequestTimestamp(Instant.now().toString());
        request.setPlateNumber(plate);
        return request;
    }

    private record Fixture(String plate, Vehicle vehicle, Passenger passenger, DevicePrincipal device) {}
}
