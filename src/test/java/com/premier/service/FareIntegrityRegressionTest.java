package com.premier.service;

import com.premier.device.model.*;
import com.premier.device.repository.DeviceRepository;
import com.premier.device.security.DevicePrincipal;
import com.premier.exception.ClientException;
import com.premier.model.*;
import com.premier.repository.*;
import com.premier.rfid.DeviceFareRequest;
import com.premier.response.FarePaymentResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class FareIntegrityRegressionTest {
    @Autowired FarePaymentService fares;
    @Autowired PassengerRepository passengers;
    @Autowired TransactionRepository transactions;
    @Autowired FareQrTokenRepository tokens;
    @Autowired DeviceRepository devices;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.premier.payment.service.PaymentIdentity identities;
    @Autowired com.premier.admin.service.AdminService adminService;
    @Autowired com.premier.admin.repository.AdminRepository admins;
    @Autowired com.premier.payment.repository.PaymentNotificationRepository notifications;
    @Autowired com.premier.payment.service.PaymentNotificationService notificationHistory;
    @Autowired com.premier.driver.repository.VehicleRepository vehicles;
    @Autowired com.premier.config.FareConfigurationRepository fareConfigurations;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void ensureActiveVehicle() {
        fareConfigurations.findById(1L).orElseGet(() -> fareConfigurations.saveAndFlush(
                com.premier.config.FareConfiguration.builder().id(1L)
                        .regularFare(new BigDecimal("75.00")).discountedFare(new BigDecimal("60.00"))
                        .currency("PHP").build()));
        var vehicle = vehicles.findByPlateNumber("TEST-01").orElseGet(() ->
                com.premier.driver.model.Vehicle.builder()
                        .plateNumber("TEST-01")
                        .totalCapacity(50)
                        .build());
        vehicle.setStatus(com.premier.driver.model.VehicleStatus.ACTIVE);
        vehicle.setRoute("SM Terminal to Grand Terminal");
        vehicles.saveAndFlush(vehicle);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(PassengerCardCategory.class)
    void newCardsAndEntityDefaultsReceive150OnlyOnce(PassengerCardCategory category) {
        String suffix = UUID.randomUUID().toString();
        var admin = admins.saveAndFlush(com.premier.admin.model.Admin.builder()
                .adminId(suffix.substring(0, 18)).username(suffix).fullName("Issuance test")
                .password("unused").is2FaEnabled(true).build());
        var existing = passenger("37.25");
        var card = adminService.createPassenger(admin, suffix.replace("-", "").substring(0, 14), category.name()).getData();
        var created = passengers.findById(card.getPassengerId()).orElseThrow();
        assertThat(created.getBalance()).isEqualByComparingTo("150.00");
        assertThat(created.getDiscountEligible()).isEqualTo(category != PassengerCardCategory.REGULAR);
        assertThat(new Passenger().getBalance()).isEqualByComparingTo("150.00");
        assertThat(Passenger.builder().cardCategory(category).build().getBalance()).isEqualByComparingTo("150.00");
        var opening = transactions.findByPassengerIdOrderByCreatedAtDesc(created.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertThat(opening).singleElement().satisfies(tx -> {
            assertThat(tx.getAmount()).isEqualByComparingTo("150.00");
            assertThat(tx.getBalanceAfter()).isEqualByComparingTo("150.00");
        });
        existing.setFcmToken("updated-profile");
        passengers.saveAndFlush(existing);
        assertThat(passengers.findById(existing.getId()).orElseThrow().getBalance()).isEqualByComparingTo("37.25");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "REGULAR,true,75.00,75.00", "STUDENT,true,60.00,90.00",
            "SENIOR_CITIZEN,true,60.00,90.00", "PWD,true,60.00,90.00",
            "STUDENT,false,75.00,75.00"})
    void officialFareForEveryCategoryAndPaymentMethod(PassengerCardCategory category,
            boolean eligible, String amount, String remaining) {
        for (String method : List.of("QR", "NFC", "RFID")) {
            var passenger = passenger("150.00");
            passenger.setCardCategory(category); passenger.setDiscountEligible(eligible);
            passenger.setRfidUid(UUID.randomUUID().toString().replace("-", "").toUpperCase());
            passenger = passengers.saveAndFlush(passenger);
            var terminal = device();
            var req = request(null, UUID.randomUUID().toString());
            java.util.function.Supplier<FarePaymentResponse> pay;
            if (method.equals("RFID")) {
                req.setRfidUid(passenger.getRfidUid());
                pay = () -> fares.processRfidPayment(req, terminal).getData();
            } else if (method.equals("NFC")) {
                req.setPayload(fares.generateMobileNfcToken(passenger).getData().getToken());
                pay = () -> fares.processMobileNfcTokenPayment(req, terminal).getData();
            } else {
                req.setPayload(fares.generateQrToken(passenger).getData().getPayload());
                pay = () -> fares.processQrPayment(req, terminal).getData();
            }
            var result = pay.get();
            assertThat(result.getBaseFare()).isEqualByComparingTo("75.00");
            assertThat(result.getDeductedFare()).isEqualByComparingTo(amount);
            assertThat(result.getRemainingBalance()).isEqualByComparingTo(remaining);
            assertThat(pay.get()).usingRecursiveComparison().isEqualTo(result);
            assertThat(ledgerCount(passenger)).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"REGULAR,74.99", "STUDENT,59.99"})
    void oneCentBelowRequiredFareIsRejected(PassengerCardCategory category, String balance) {
        var passenger = passenger(balance);
        passenger.setCardCategory(category); passenger.setDiscountEligible(true);
        passenger = passengers.saveAndFlush(passenger);
        var req = request(fares.generateQrToken(passenger).getData().getPayload(), UUID.randomUUID().toString());
        var terminal = device();
        assertThatThrownBy(() -> fares.processQrPayment(req, terminal))
                .isInstanceOf(ClientException.class).extracting("code").isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(ledgerCount(passenger)).isZero();
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getBalance()).isEqualByComparingTo(balance);
    }

    @ParameterizedTest
    @ValueSource(strings = {"60.00", "48.00"})
    void historicalQrStatusKeepsRecordedAmount(String oldFare) {
        var passenger = passenger("120.00");
        String payload = fares.generateQrToken(passenger).getData().getPayload();
        String reference = "LEGACY-" + UUID.randomUUID();
        var historical = transactions.saveAndFlush(Transaction.builder().passenger(passenger)
                .type(TransactionType.FARE_DEDUCTION).status(TransactionStatus.SUCCESS)
                .amount(new BigDecimal(oldFare)).balanceBefore(new BigDecimal("180.00"))
                .balanceAfter(new BigDecimal("180.00").subtract(new BigDecimal(oldFare)))
                .referenceNumber(reference).build());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var token = tokens.findByPassengerIdAndStatus(passenger.getId(), FareQrTokenStatus.ACTIVE).get(0);
            token.setStatus(FareQrTokenStatus.USED); token.setUsedReferenceNumber(reference);
            tokens.save(token);
        });
        var payment = fares.getQrTokenStatus(passenger, payload).getData().getPayment();
        assertThat(payment.getDeductedFare()).isEqualByComparingTo(oldFare);
        assertThat(payment.getBaseFare()).isNull();
        assertThat(transactions.findById(historical.getId()).orElseThrow().getAmount()).isEqualByComparingTo(oldFare);
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getBalance()).isEqualByComparingTo("120.00");
    }

    Passenger passenger(String balance) {
        String id = UUID.randomUUID().toString();
        return passengers.saveAndFlush(Passenger.builder().cardNumber("test-" + id).rfidUid(id)
                .status(PassengerStatus.ACTIVE).is2FaEnabled(true).balance(new BigDecimal(balance)).build());
    }
    DevicePrincipal device() {
        var vehicle = vehicles.findByPlateNumber("TEST-01").orElseThrow();
        return DevicePrincipal.from(devices.saveAndFlush(Device.builder().deviceId(UUID.randomUUID().toString())
                .deviceName("isolated test terminal").deviceType(DeviceType.VEHICLE_TERMINAL)
                .vehicleId(vehicle.getId()).plateNumber("TEST-01").tokenHash("not-a-production-credential").build()));
    }
    DeviceFareRequest request(String payload, String key) {
        var request = new DeviceFareRequest();
        request.setPayload(payload); request.setIdempotencyKey(key); request.setPlateNumber("TEST-01");
        request.setRequestNonce(UUID.randomUUID().toString()); request.setRequestTimestamp(Instant.now().toString());
        return request;
    }
    List<Object> race(int count, IntFunction<Object> action) throws Exception {
        var executor = Executors.newFixedThreadPool(count);
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        try {
            List<Future<Object>> pending = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                pending.add(executor.submit(() -> {
                    ready.countDown(); start.await();
                    try { return action.apply(index); } catch (RuntimeException ex) { return ex; }
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<Object> results = new ArrayList<>();
            for (var future : pending) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); executor.shutdownNow(); }
    }
    long ledgerCount(Passenger passenger) {
        return transactions.findByPassengerIdOrderByCreatedAtDesc(passenger.getId(), org.springframework.data.domain.PageRequest.of(0, 200)).getTotalElements();
    }
    @Test void successfulRfidFareCreatesOnePassengerNotification() {
        var passenger = passenger("200.00");
        passenger.setRfidUid(UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        passenger = passengers.saveAndFlush(passenger);
        var terminalRequest = request(null, UUID.randomUUID().toString());
        terminalRequest.setRfidUid(passenger.getRfidUid());
        var payment = fares.processRfidPayment(terminalRequest, device()).getData();
        Long passengerId = passenger.getId();

        var passengerNotifications = notifications.findAll().stream()
                .filter(notification -> notification.getPassengerId().equals(passengerId))
                .toList();

        assertThat(payment.getSource()).isEqualTo("RFID");
        assertThat(payment.getRemainingBalance()).isEqualByComparingTo("125.00");
        assertThat(passengerNotifications).singleElement().satisfies(notification -> {
            assertThat(notification.getKind()).isEqualTo("FARE");
            assertThat(notification.getReference()).isEqualTo(payment.getReferenceNumber());
            assertThat(notification.getStatus()).isEqualTo("PENDING");
        });
        var history = notificationHistory.history(passenger, 0, 20).getData();
        assertThat(history.getContent()).singleElement().satisfies(notification -> {
            assertThat(notification.getType()).isEqualTo("FARE");
            assertThat(notification.getReference()).isEqualTo(payment.getReferenceNumber());
            assertThat(notification.isRead()).isFalse();
        });
        var otherPassenger = passenger("200.00");
        assertThat(notificationHistory.history(otherPassenger, 0, 20).getData().getContent()).isEmpty();
        notificationHistory.markRead(passenger, history.getContent().get(0).getId());
        assertThat(notificationHistory.history(passenger, 0, 20).getData().getUnreadCount()).isZero();
    }
    @ParameterizedTest @ValueSource(ints = {2, 10, 100})
    void oneQrAllowsOnlyOneFareAcrossTerminals(int count) throws Exception {
        var passenger = passenger("6000.00");
        String payload = fares.generateQrToken(passenger).getData().getPayload();
        var terminals = new ArrayList<DevicePrincipal>();
        for (int i = 0; i < count; i++) terminals.add(device());
        var result = race(count, i -> fares.processQrPayment(request(payload, UUID.randomUUID().toString()), terminals.get(i)).getData());
        assertThat(result.stream().filter(FarePaymentResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(result.stream().filter(ClientException.class::isInstance).count()).isEqualTo(count - 1);
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getBalance()).isEqualByComparingTo("5925.00");
        assertThat(ledgerCount(passenger)).isEqualTo(1);
        assertThat(notifications.findAll().stream().filter(n -> n.getPassengerId().equals(passenger.getId())).count()).isEqualTo(1);
    }
    @Test void lostResponseAndConcurrentRetriesReturnOriginalResult() throws Exception {
        var passenger = passenger("200.00"); var terminal = device();
        String payload = fares.generateQrToken(passenger).getData().getPayload();
        String key = UUID.randomUUID().toString();
        var results = race(10, i -> fares.processQrPayment(request(payload, key), terminal).getData());
        assertThat(results).allMatch(FarePaymentResponse.class::isInstance);
        var first = (FarePaymentResponse) results.get(0);
        assertThat(results).allSatisfy(value -> assertThat(value).usingRecursiveComparison().isEqualTo(first));
        assertThat(results).extracting(value -> ((FarePaymentResponse)value).getReferenceNumber()).containsOnly(first.getReferenceNumber());
        assertThat(results).extracting(value -> ((FarePaymentResponse)value).getRemainingBalance()).allMatch(value -> ((BigDecimal)value).compareTo(new BigDecimal("125.00")) == 0);
        assertThat(ledgerCount(passenger)).isEqualTo(1);
        var retry = request(payload, key); retry.setRequestTimestamp(Instant.now().minusSeconds(900).toString());
        assertThat(fares.processQrPayment(retry, terminal).getData().getReferenceNumber()).isEqualTo(first.getReferenceNumber());
        assertThatThrownBy(() -> fares.processQrPayment(request(payload, key), device())).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> fares.processQrPayment(request("different-payload", key), terminal)).isInstanceOf(ClientException.class);
    }
    @Test void expiredOfflineAndWrongPurposeCannotDebit() {
        var passenger = passenger("200.00"); var terminal = device();
        String payload = fares.generateQrToken(passenger).getData().getPayload();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var token = tokens.findByPassengerIdAndStatus(passenger.getId(), FareQrTokenStatus.ACTIVE).get(0);
            token.setExpiresAt(LocalDateTime.now(ZoneId.of("Asia/Manila")).minusMinutes(1)); tokens.save(token);
        });
        var request = request(payload, UUID.randomUUID().toString()); request.setOfflineSync(true);
        request.setOfflineCapturedAt(Instant.now().minusSeconds(120).toString());
        assertThatThrownBy(() -> fares.processQrPayment(request, terminal)).isInstanceOf(RuntimeException.class);
        String nfc = fares.generateMobileNfcToken(passenger).getData().getToken();
        assertThatThrownBy(() -> fares.processQrPayment(request(nfc, UUID.randomUUID().toString()), terminal)).isInstanceOf(ClientException.class);
        assertThat(ledgerCount(passenger)).isZero();
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getBalance()).isEqualByComparingTo("200.00");
    }

    @Test void qrCapturedInsideValidityWindowCanSyncAfterExpiry() {
        var passenger = passenger("200.00"); var terminal = device();
        String payload = fares.generateQrToken(passenger).getData().getPayload();
        var token = tokens.findByPassengerIdAndStatus(passenger.getId(), FareQrTokenStatus.ACTIVE).get(0);
        LocalDateTime created = LocalDateTime.now(ZoneId.of("Asia/Manila")).minusMinutes(2);
        LocalDateTime expiry = created.plusSeconds(60);
        jdbc.update("update fare_qr_tokens set created_at = ?, expires_at = ? where id = ?", created, expiry, token.getId());
        var replay = request(payload, UUID.randomUUID().toString()); replay.setOfflineSync(true);
        replay.setOfflineTransactionId(replay.getIdempotencyKey());
        replay.setOfflineCapturedAt(created.plusSeconds(30).atZone(ZoneId.of("Asia/Manila")).toInstant().toString());
        var first = fares.processQrPayment(replay, terminal).getData();
        var retry = fares.processQrPayment(replay, terminal).getData();
        assertThat(retry.getReferenceNumber()).isEqualTo(first.getReferenceNumber());
        assertThat(ledgerCount(passenger)).isEqualTo(1);
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getBalance()).isEqualByComparingTo("125.00");
    }

    @Test void qrExpiredAtCaptureTimeCannotSyncOrDebit() {
        var passenger = passenger("200.00"); var terminal = device();
        String payload = fares.generateQrToken(passenger).getData().getPayload();
        var token = tokens.findByPassengerIdAndStatus(passenger.getId(), FareQrTokenStatus.ACTIVE).get(0);
        LocalDateTime created = LocalDateTime.now(ZoneId.of("Asia/Manila")).minusMinutes(2);
        LocalDateTime expiry = created.plusSeconds(60);
        jdbc.update("update fare_qr_tokens set created_at = ?, expires_at = ? where id = ?", created, expiry, token.getId());
        var replay = request(payload, UUID.randomUUID().toString()); replay.setOfflineSync(true);
        replay.setOfflineTransactionId(replay.getIdempotencyKey());
        replay.setOfflineCapturedAt(expiry.plusSeconds(5).atZone(ZoneId.of("Asia/Manila")).toInstant().toString());
        assertThatThrownBy(() -> fares.processQrPayment(replay, terminal)).isInstanceOf(ClientException.class)
                .extracting("code").isEqualTo("QR_EXPIRED");
        assertThat(ledgerCount(passenger)).isZero();
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getBalance()).isEqualByComparingTo("200.00");
    }

    @Test void nfcCapturedInsideValidityWindowCanSyncAfterExpiry() {
        var passenger = passenger("200.00"); var terminal = device();
        String tokenValue = fares.generateMobileNfcToken(passenger).getData().getToken();
        var token = tokens.findByPassengerIdAndStatus(passenger.getId(), FareQrTokenStatus.ACTIVE).get(0);
        LocalDateTime created = LocalDateTime.now(ZoneId.of("Asia/Manila")).minusMinutes(2);
        LocalDateTime expiry = created.plusSeconds(60);
        jdbc.update("update fare_qr_tokens set created_at = ?, expires_at = ? where id = ?", created, expiry, token.getId());
        var replay = request(tokenValue, UUID.randomUUID().toString()); replay.setOfflineSync(true);
        replay.setOfflineTransactionId(replay.getIdempotencyKey());
        replay.setOfflineCapturedAt(created.plusSeconds(30).atZone(ZoneId.of("Asia/Manila")).toInstant().toString());
        var first = fares.processMobileNfcTokenPayment(replay, terminal).getData();
        var retry = fares.processMobileNfcTokenPayment(replay, terminal).getData();
        assertThat(retry.getReferenceNumber()).isEqualTo(first.getReferenceNumber());
        assertThat(ledgerCount(passenger)).isEqualTo(1);
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getBalance()).isEqualByComparingTo("125.00");
    }

    @Test void offlineRfidReplayDeductsOnceWithoutAnotherTap() {
        var passenger = passenger("200.00"); var terminal = device();
        passenger.setRfidUid(UUID.randomUUID().toString().replace("-", "").toUpperCase());
        passenger = passengers.saveAndFlush(passenger);
        var replay = request(null, UUID.randomUUID().toString()); replay.setRfidUid(passenger.getRfidUid());
        replay.setOfflineSync(true); replay.setOfflineTransactionId(replay.getIdempotencyKey());
        replay.setOfflineCapturedAt(Instant.now().minusSeconds(90).toString());
        var first = fares.processRfidPayment(replay, terminal).getData();
        var retry = fares.processRfidPayment(replay, terminal).getData();
        assertThat(retry.getReferenceNumber()).isEqualTo(first.getReferenceNumber());
        assertThat(ledgerCount(passenger)).isEqualTo(1);
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getBalance()).isEqualByComparingTo("125.00");
    }
    @Test void replayCannotChangeCapturedTimeRequestOrFare() {
        var passenger = passenger("200.00"); var terminal = device();
        String payload = fares.generateQrToken(passenger).getData().getPayload(); String key = UUID.randomUUID().toString();
        fares.processQrPayment(request(payload, key), terminal);
        var changed = request(payload, key); changed.setFareAmount(new BigDecimal("1.00"));
        assertThatThrownBy(() -> fares.processQrPayment(changed, terminal)).isInstanceOf(ClientException.class);
        changed.setFareAmount(null); changed.setOfflineCapturedAt(Instant.now().minusSeconds(60).toString());
        assertThatThrownBy(() -> fares.processQrPayment(changed, terminal)).isInstanceOf(ClientException.class);
        changed.setOfflineCapturedAt(null); changed.setRequestId("changed-request");
        assertThatThrownBy(() -> fares.processQrPayment(changed, terminal)).isInstanceOf(ClientException.class);
        assertThat(ledgerCount(passenger)).isEqualTo(1);
    }
    @Test void insufficientFundsAndStaleEntityCannotOverwriteWallet() {
        var passenger = passenger("50.00"); var terminal = device();
        String payload = fares.generateQrToken(passenger).getData().getPayload();
        assertThatThrownBy(() -> fares.processQrPayment(request(payload, UUID.randomUUID().toString()), terminal))
                .isInstanceOf(ClientException.class).extracting("code").isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(ledgerCount(passenger)).isZero();
        var funded = passenger("200.00"); var stale = passengers.findById(funded.getId()).orElseThrow();
        fares.processQrPayment(request(fares.generateQrToken(funded).getData().getPayload(), UUID.randomUUID().toString()), terminal);
        stale.setFcmToken("synthetic-update");
        assertThatThrownBy(() -> passengers.saveAndFlush(stale)).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(passengers.findById(funded.getId()).orElseThrow().getBalance()).isEqualByComparingTo("125.00");
    }
    @Test void sharedIdentityCannotCrossPaymentMethods() {
        var terminal = device(); String key = UUID.randomUUID().toString(); var request = request("test", key);
        new TransactionTemplate(manager).executeWithoutResult(status -> identities.claim(key, request, terminal, "QR"));
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> identities.claim(key, request, terminal, "CASH")))
                .isInstanceOf(ClientException.class);
    }
    @Test void concurrentAdminCreditAndFareReconcileWithLedger() throws Exception {
        var passenger = passenger("200.00"); var terminal = device();
        String payload = fares.generateQrToken(passenger).getData().getPayload();
        String name = UUID.randomUUID().toString();
        var admin = admins.saveAndFlush(com.premier.admin.model.Admin.builder().adminId(name.substring(0, 18))
                .username(name).fullName("Test admin").password("not-a-credential").is2FaEnabled(true).build());
        var results = race(2, i -> i == 0 ? fares.processQrPayment(request(payload, UUID.randomUUID().toString()), terminal)
                : adminService.addBalance(admin, passenger.getId(), new BigDecimal("100.00"), "Synthetic reconciliation test", "127.0.0.1"));
        assertThat(results).noneMatch(RuntimeException.class::isInstance);
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getBalance()).isEqualByComparingTo("225.00");
        var rows = transactions.findByPassengerIdOrderByCreatedAtDesc(passenger.getId(), org.springframework.data.domain.PageRequest.of(0, 20)).getContent();
        assertThat(rows).hasSize(2);
        BigDecimal delta = rows.stream().map(row -> row.getBalanceAfter().subtract(row.getBalanceBefore())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(delta).isEqualByComparingTo("25.00");
    }
    @Test void rolledBackFareCanRetrySameIdentityAndNonce() {
        var passenger = passenger("200.00"); var terminal = device();
        var request = request(fares.generateQrToken(passenger).getData().getPayload(), UUID.randomUUID().toString());
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            fares.processQrPayment(request, terminal);
            throw new IllegalStateException("synthetic rollback before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(ledgerCount(passenger)).isZero();
        assertThat(fares.processQrPayment(request, terminal).getData().getRemainingBalance()).isEqualByComparingTo("125.00");
        assertThat(ledgerCount(passenger)).isEqualTo(1);
    }
    @Test void exactDiscountAndZeroBalanceAreEnforced() {
        var passenger = passenger("60.00"); passenger.setCardCategory(PassengerCardCategory.STUDENT); passenger.setDiscountEligible(true);
        passenger = passengers.saveAndFlush(passenger); var terminal = device();
        var result = fares.processQrPayment(request(fares.generateQrToken(passenger).getData().getPayload(), UUID.randomUUID().toString()), terminal).getData();
        assertThat(result.getDeductedFare()).isEqualByComparingTo("60.00"); assertThat(result.getRemainingBalance()).isEqualByComparingTo("0.00");
        var next = request(fares.generateQrToken(passenger).getData().getPayload(), UUID.randomUUID().toString());
        assertThatThrownBy(() -> fares.processQrPayment(next, terminal)).isInstanceOf(ClientException.class);
        assertThat(ledgerCount(passenger)).isEqualTo(1);
    }
    @Test void concurrentFareReversalsCreateOneLinkedCorrection() throws Exception {
        var passenger = passenger("200.00"); var terminal = device();
        fares.processQrPayment(request(fares.generateQrToken(passenger).getData().getPayload(), UUID.randomUUID().toString()), terminal);
        var original = transactions.findByPassengerIdOrderByCreatedAtDesc(passenger.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent().get(0);
        String name = UUID.randomUUID().toString();
        var admin = admins.saveAndFlush(com.premier.admin.model.Admin.builder().adminId(name.substring(0, 18))
                .username(name).fullName("Test supervisor").password("not-a-credential").is2FaEnabled(true)
                .role(com.premier.admin.model.AdminRole.SUPER_ADMIN).build());
        var results = race(10, i -> adminService.reverseFare(admin, original.getId(), "Verified synthetic duplicate-fare complaint"));
        assertThat(results).noneMatch(RuntimeException.class::isInstance);
        assertThat(ledgerCount(passenger)).isEqualTo(2);
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getBalance()).isEqualByComparingTo("200.00");
        var unchanged = transactions.findById(original.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(unchanged.getBalanceAfter()).isEqualByComparingTo("125.00");
        assertThat(transactions.findByReversalOfId(original.getId())).isPresent();
        admin.setRole(com.premier.admin.model.AdminRole.ADMIN);
        assertThatThrownBy(() -> adminService.reverseFare(admin, original.getId(), "Unauthorized attempt for regression")).isInstanceOf(ClientException.class);
    }
}
