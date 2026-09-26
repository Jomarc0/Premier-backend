package com.premier.service;

import com.premier.model.*;
import com.premier.repository.*;
import com.premier.request.TopUpRequestDto;
import com.premier.response.ApiResponse;
import com.premier.response.TopUpResponse;
import com.premier.exception.ClientException;
import com.premier.payment.repository.ProviderEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@SpringBootTest @ActiveProfiles("test")
class PayMongoSettlementRegressionTest {
    @Autowired PayMongoService paymongo;
    @Autowired PassengerRepository passengers;
    @Autowired TopUpRequestRepository topups;
    @Autowired TransactionRepository transactions;
    @Autowired ProviderEventRepository events;
    @Autowired RestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired com.premier.payment.repository.PaymentNotificationRepository notifications;
    MockRestServiceServer server;

    @BeforeEach void setup() { server = MockRestServiceServer.bindTo(rest).build(); }
    @AfterEach void verify() { server.verify(); }
    Passenger passenger() {
        return passengers.saveAndFlush(Passenger.builder().cardNumber(UUID.randomUUID().toString())
                .status(PassengerStatus.ACTIVE).is2FaEnabled(true).balance(new BigDecimal("120.00")).build());
    }
    TopUpRequest pending(Passenger passenger) {
        return topups.saveAndFlush(TopUpRequest.builder().passenger(passenger).amount(new BigDecimal("100.00"))
                .paymongoLinkId("link_" + UUID.randomUUID().toString().replace("-", ""))
                .idempotencyKey(UUID.randomUUID().toString()).paymentMethod("GCASH")
                .referenceNumber("PMR-" + UUID.randomUUID()).build());
    }
    Map<String, Object> resource(TopUpRequest request, int amount, String currency, String status) {
        return Map.of("id", request.getPaymongoLinkId(), "type", "link", "attributes",
                Map.of("amount", amount, "currency", currency, "status", status, "livemode", false));
    }
    String body(TopUpRequest request, String event, int amount, String currency, String status) throws Exception {
        return mapper.writeValueAsString(Map.of("data", Map.of("id", event, "type", "event", "attributes",
                Map.of("type", "link.payment.paid", "livemode", false, "data", resource(request, amount, currency, status)))));
    }
    String sign(String body, long timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-paymongo-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",te=" + signature + ",li=";
    }
    long count(Passenger passenger) { return transactions.findByPassengerIdOrderByCreatedAtDesc(passenger.getId(), org.springframework.data.domain.PageRequest.of(0, 50)).getTotalElements(); }
    @Test void concurrentDuplicateWebhooksCreditExactlyOnce() throws Exception {
        var passenger = passenger(); var request = pending(passenger);
        String event = "evt_" + UUID.randomUUID().toString().replace("-", "");
        String body = body(request, event, 10000, "PHP", "paid"); String signature = sign(body, Instant.now().getEpochSecond());
        var executor = Executors.newFixedThreadPool(10); var start = new CountDownLatch(1);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) calls.add(executor.submit(() -> { start.await(); paymongo.handleWebhook(body, signature); return null; }));
            start.countDown(); for (var call : calls) call.get(30, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getBalance()).isEqualByComparingTo("220.00");
        assertThat(count(passenger)).isEqualTo(1); assertThat(events.findById(event)).isPresent();
        assertThat(paymongo.processPayment(passenger, request.getReferenceNumber()).getData().get("newBalance")).isEqualTo(new BigDecimal("220.00"));
        assertThat(notifications.findAll().stream().filter(n -> n.getReference().equals(request.getReferenceNumber())).toList())
                .singleElement().satisfies(n -> {
                    assertThat(n.getPassengerId()).isEqualTo(passenger.getId());
                    assertThat(n.getKind()).isEqualTo("TOPUP");
                    assertThat(n.getStatus()).isEqualTo("PENDING");
                });
    }
    @Test void invalidSignatureTimestampModeAndTamperedBodyNeverCredit() throws Exception {
        var passenger = passenger(); var request = pending(passenger);
        String body = body(request, "evt_" + UUID.randomUUID(), 10000, "PHP", "paid");
        String valid = sign(body, Instant.now().getEpochSecond());
        assertThatThrownBy(() -> paymongo.handleWebhook(body + " ", valid)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> paymongo.handleWebhook(body, sign(body, Instant.now().minusSeconds(600).getEpochSecond()))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> paymongo.handleWebhook(body, valid.replace(",te=", ",SWAP=").replace(",li=", ",te=").replace(",SWAP=", ",li="))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> paymongo.handleWebhook(body, valid + ",t=1")).isInstanceOf(SecurityException.class);
        assertThat(count(passenger)).isZero();
    }
    @Test void mismatchAmountCurrencyStatusAndUnknownLinkDoNotCredit() throws Exception {
        var passenger = passenger(); var request = pending(passenger);
        for (String body : List.of(body(request, "evt_" + UUID.randomUUID(), 9999, "PHP", "paid"),
                body(request, "evt_" + UUID.randomUUID(), 10000, "USD", "paid"),
                body(request, "evt_" + UUID.randomUUID(), 10000, "PHP", "unpaid"))) {
            assertThatThrownBy(() -> paymongo.handleWebhook(body, sign(body, Instant.now().getEpochSecond()))).isInstanceOf(ClientException.class);
        }
        assertThat(count(passenger)).isZero();
        assertThat(topups.findById(request.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.PENDING);
    }
    @Test void verifyTimeoutIsUnavailableNotUnpaidAndDoesNotHoldTransaction() {
        var passenger = passenger(); var request = pending(passenger);
        server.expect(requestTo("https://api.paymongo.com/v1/links/" + request.getPaymongoLinkId()))
                .andExpect(http -> assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse())
                .andRespond(withException(new java.net.SocketTimeoutException("synthetic timeout")));
        assertThatThrownBy(() -> paymongo.processPayment(passenger, request.getReferenceNumber())).isInstanceOf(ClientException.class)
                .extracting("code").isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(count(passenger)).isZero();
    }
    @Test void initiationTimeoutKeepsRecoveryReferenceAndNeverRetriesPost() {
        var passenger = passenger(); var dto = new TopUpRequestDto(); dto.setAmount(new BigDecimal("100.00"));
        dto.setIdempotencyKey(UUID.randomUUID().toString());
        server.expect(requestTo("https://api.paymongo.com/v1/links"))
                .andExpect(http -> assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse())
                .andRespond(withException(new java.net.SocketTimeoutException("synthetic timeout")));
        var response = paymongo.initiateTopUp(passenger, dto);
        assertThat(response.isSuccess()).isFalse(); assertThat(response.getCode()).isEqualTo("SERVICE_UNAVAILABLE");
        var request = topups.findByReferenceNumber(response.getReference()).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(TransactionStatus.PROCESSING); assertThat(request.getPaymongoLinkId()).isNull();
        var retry = paymongo.initiateTopUp(passenger, dto);
        assertThat(retry.getData().getTopUpId()).isEqualTo(request.getId());
        assertThat(topups.findAll().stream().filter(t -> dto.getIdempotencyKey().equals(t.getIdempotencyKey()))).hasSize(1);
        assertThat(count(passenger)).isZero();
    }
    @Test void concurrentInitiationWithSameKeyCreatesOneTopUpAndOneProviderLink() throws Exception {
        var passenger = passenger(); var dto = new TopUpRequestDto();
        dto.setAmount(new BigDecimal("100.00")); dto.setPaymentMethod("GCASH");
        dto.setIdempotencyKey(UUID.randomUUID().toString());
        String linkId = "link_" + UUID.randomUUID().toString().replace("-", "");
        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo("https://api.paymongo.com/v1/links"))
                .andRespond(withSuccess(mapper.writeValueAsString(Map.of("data", Map.of(
                        "id", linkId, "type", "link", "attributes", Map.of(
                                "amount", 10000, "currency", "PHP", "status", "active",
                                "livemode", false, "checkout_url", "https://checkout.paymongo.com/test")))),
                        org.springframework.http.MediaType.APPLICATION_JSON));
        var executor = Executors.newFixedThreadPool(10); var start = new CountDownLatch(1);
        try {
            List<Future<ApiResponse<TopUpResponse>>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) calls.add(executor.submit(() -> {
                start.await();
                return paymongo.initiateTopUp(passenger, dto);
            }));
            start.countDown();
            for (var call : calls) assertThat(call.get(30, TimeUnit.SECONDS).getData()).isNotNull();
        } finally { executor.shutdownNow(); }
        assertThat(topups.findAll().stream().filter(t -> dto.getIdempotencyKey().equals(t.getIdempotencyKey())))
                .singleElement().satisfies(t -> assertThat(t.getPaymongoLinkId()).isEqualTo(linkId));
    }
    @Test void providerReconciliationRepairsMissedWebhookOnce() throws Exception {
        var passenger = passenger(); var request = pending(passenger);
        server.expect(requestTo("https://api.paymongo.com/v1/links/" + request.getPaymongoLinkId()))
                .andExpect(http -> assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse())
                .andRespond(withSuccess(mapper.writeValueAsString(Map.of("data", resource(request, 10000, "PHP", "paid"))), org.springframework.http.MediaType.APPLICATION_JSON));
        assertThat(paymongo.reconcile(request.getId())).isTrue(); assertThat(paymongo.reconcile(request.getId())).isTrue();
        assertThat(count(passenger)).isEqualTo(1);
    }
    @Test void extraPrecisionRejectedBeforeAnyProviderSubmission() {
        var dto = new TopUpRequestDto(); dto.setAmount(new BigDecimal("100.009"));
        assertThatThrownBy(() -> paymongo.initiateTopUp(passenger(), dto)).isInstanceOf(ClientException.class);
    }
    @Test void pendingRecoveryIsOwnedAndBoundedAndCancelledPaymentCannotSettle() throws Exception {
        var owner = passenger(); var other = passenger(); var request = pending(owner); var foreign = pending(other);
        String recovery = mapper.writeValueAsString(paymongo.pendingTopUps(owner, 0));
        assertThat(recovery).contains(request.getReferenceNumber()).doesNotContain(foreign.getReferenceNumber(), "paymongoLinkId");
        assertThatThrownBy(() -> paymongo.checkPaymentStatus(owner, foreign.getReferenceNumber())).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> paymongo.pendingTopUps(owner, -1)).isInstanceOf(ClientException.class);
        request.setStatus(TransactionStatus.CANCELLED); topups.saveAndFlush(request);
        String paid = body(request, "evt_" + UUID.randomUUID(), 10000, "PHP", "paid");
        assertThatThrownBy(() -> paymongo.handleWebhook(paid, sign(paid, Instant.now().getEpochSecond()))).isInstanceOf(ClientException.class);
        assertThat(count(owner)).isZero();
    }
    @Test void unmatchedPaidEventIsDurableAndLaterFailedEventCannotUndoCredit() throws Exception {
        var passenger = passenger(); var request = pending(passenger);
        String eventId = "evt_" + UUID.randomUUID();
        String paid = body(request, eventId, 10000, "PHP", "paid");
        String link = request.getPaymongoLinkId(); request.setPaymongoLinkId(null); topups.saveAndFlush(request);
        assertThatThrownBy(() -> paymongo.handleWebhook(paid, sign(paid, Instant.now().getEpochSecond()))).isInstanceOf(ClientException.class);
        assertThat(events.findById(eventId).orElseThrow().getStatus()).isEqualTo("RECEIVED");
        request.setPaymongoLinkId(link); topups.saveAndFlush(request);
        paymongo.handleWebhook(paid, sign(paid, Instant.now().getEpochSecond()));
        String failedId = "evt_" + UUID.randomUUID();
        String failed = body(request, failedId, 10000, "PHP", "failed").replace("link.payment.paid", "payment.failed");
        paymongo.handleWebhook(failed, sign(failed, Instant.now().getEpochSecond()));
        assertThat(events.findById(failedId).orElseThrow().getStatus()).isEqualTo("REVIEW");
        assertThat(topups.findById(request.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(count(passenger)).isEqualTo(1);
    }
}
