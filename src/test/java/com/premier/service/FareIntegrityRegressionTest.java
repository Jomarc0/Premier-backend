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

    Passenger passenger(String balance) {
        String id = UUID.randomUUID().toString();
        return passengers.saveAndFlush(Passenger.builder().cardNumber("test-" + id).rfidUid(id)
                .status(PassengerStatus.ACTIVE).is2FaEnabled(true).balance(new BigDecimal(balance)).build());
    }
    DevicePrincipal device() {
        return DevicePrincipal.from(devices.saveAndFlush(Device.builder().deviceId(UUID.randomUUID().toString())
                .deviceName("isolated test terminal").deviceType(DeviceType.VEHICLE_TERMINAL)
                .plateNumber("TEST-01").tokenHash("not-a-production-credential").build()));
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
    @ParameterizedTest @ValueSource(ints = {2, 10, 100})
    void oneQrAllowsOnlyOneFareAcrossTerminals(int count) throws Exception {
        var passenger = passenger("6000.00");
        String payload = fares.generateQrToken(passenger).getData().getPayload();
        var terminals = new ArrayList<DevicePrincipal>();
        for (int i = 0; i < count; i++) terminals.add(device());
        var result = race(count, i -> fares.processQrPayment(request(payload, UUID.randomUUID().toString()), terminals.get(i)).getData());
        assertThat(result.stream().filter(FarePaymentResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(result.stream().filter(ClientException.class::isInstance).count()).isEqualTo(count - 1);
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getBalance()).isEqualByComparingTo("5940.00");
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
        assertThat(results).extracting(value -> ((FarePaymentResponse)value).getRemainingBalance()).allMatch(value -> ((BigDecimal)value).compareTo(new BigDecimal("140.00")) == 0);
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
        assertThat(passengers.findById(funded.getId()).orElseThrow().getBalance()).isEqualByComparingTo("140.00");
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
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getBalance()).isEqualByComparingTo("240.00");
        var rows = transactions.findByPassengerIdOrderByCreatedAtDesc(passenger.getId(), org.springframework.data.domain.PageRequest.of(0, 20)).getContent();
        assertThat(rows).hasSize(2);
        BigDecimal delta = rows.stream().map(row -> row.getBalanceAfter().subtract(row.getBalanceBefore())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(delta).isEqualByComparingTo("40.00");
    }
    @Test void rolledBackFareCanRetrySameIdentityAndNonce() {
        var passenger = passenger("200.00"); var terminal = device();
        var request = request(fares.generateQrToken(passenger).getData().getPayload(), UUID.randomUUID().toString());
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            fares.processQrPayment(request, terminal);
            throw new IllegalStateException("synthetic rollback before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(ledgerCount(passenger)).isZero();
        assertThat(fares.processQrPayment(request, terminal).getData().getRemainingBalance()).isEqualByComparingTo("140.00");
        assertThat(ledgerCount(passenger)).isEqualTo(1);
    }
    @Test void exactDiscountAndZeroBalanceAreEnforced() {
        var passenger = passenger("48.00"); passenger.setCardCategory(PassengerCardCategory.STUDENT); passenger.setDiscountEligible(true);
        passenger = passengers.saveAndFlush(passenger); var terminal = device();
        var result = fares.processQrPayment(request(fares.generateQrToken(passenger).getData().getPayload(), UUID.randomUUID().toString()), terminal).getData();
        assertThat(result.getDeductedFare()).isEqualByComparingTo("48.00"); assertThat(result.getRemainingBalance()).isEqualByComparingTo("0.00");
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
        assertThat(unchanged.getBalanceAfter()).isEqualByComparingTo("140.00");
        assertThat(transactions.findByReversalOfId(original.getId())).isPresent();
        admin.setRole(com.premier.admin.model.AdminRole.ADMIN);
        assertThatThrownBy(() -> adminService.reverseFare(admin, original.getId(), "Unauthorized attempt for regression")).isInstanceOf(ClientException.class);
    }
}
