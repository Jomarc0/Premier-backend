package com.premier.service;

import com.google.api.core.ApiFutures;
import com.google.firebase.messaging.*;
import com.premier.controller.DevelopmentNotificationController;
import com.premier.model.*;
import com.premier.payment.service.PaymentPushSender;
import com.premier.repository.PassengerFcmTokenRepository;
import com.premier.request.FcmTokenRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FcmSenderDiagnosticTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "TOPUP,TOPUP,100,240,Top-up successful,\u20b1100.00 added. New balance: \u20b1240.00.",
        "FARE,FARE_DEDUCTION,48,152,Fare deducted,\u20b148.00 paid. Remaining balance: \u20b1152.00."
    })
    void paymentTextUsesOwnedLedgerAmountAndClosingBalance(String kind, TransactionType type,
            String amount, String balance, String title, String body) throws Exception {
        var repository = mock(com.premier.repository.TransactionRepository.class);
        var owner = new Passenger(); owner.setId(2L); owner.setBalance(new java.math.BigDecimal("9999.00"));
        var transaction = Transaction.builder().passenger(owner).type(type).status(TransactionStatus.SUCCESS)
                .amount(new java.math.BigDecimal(amount)).balanceAfter(new java.math.BigDecimal(balance)).build();
        when(repository.findByReferenceNumberAndPassengerId("synthetic-reference", 2L)).thenReturn(Optional.of(transaction));
        var messaging = mock(FirebaseMessaging.class);
        when(messaging.sendAsync(any(Message.class))).thenReturn(ApiFutures.immediateFuture("projects/synthetic/messages/1"));
        try (var firebase = mockStatic(FirebaseMessaging.class)) {
            firebase.when(FirebaseMessaging::getInstance).thenReturn(messaging);
            new PaymentPushSender(repository).sendPayment(2L, "synthetic-device", kind, "synthetic-reference", 1000);
        }
        var message = org.mockito.ArgumentCaptor.forClass(Message.class); verify(messaging).sendAsync(message.capture());
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                com.google.api.client.json.gson.GsonFactory.getDefaultInstance().toString(message.getValue()));
        assertThat(payload.at("/notification/title").asText()).isEqualTo(title);
        assertThat(payload.at("/notification/body").asText()).isEqualTo(body).doesNotContain("9999");
        assertThat(payload.at("/android/notification/channel_id").asText()).isEqualTo("default");
        assertThat(payload.at("/data/type").asText()).isEqualTo(kind);
        assertThat(payload.at("/data/reference").asText()).isEqualTo("synthetic-reference");
        verify(repository).findByReferenceNumberAndPassengerId("synthetic-reference", 2L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "incomplete", "failed", "wrong-kind"})
    void unavailableOrIncompatibleDetailsKeepGenericNotification(String scenario) throws Exception {
        var repository = mock(com.premier.repository.TransactionRepository.class);
        var transaction = Transaction.builder().type("wrong-kind".equals(scenario) ? TransactionType.TOPUP : TransactionType.FARE_DEDUCTION)
                .status("failed".equals(scenario) ? TransactionStatus.FAILED : TransactionStatus.SUCCESS)
                .amount(new java.math.BigDecimal("60.00"))
                .balanceAfter("incomplete".equals(scenario) ? null : new java.math.BigDecimal("140.00")).build();
        when(repository.findByReferenceNumberAndPassengerId("synthetic-reference", 2L))
                .thenReturn("missing".equals(scenario) ? Optional.empty() : Optional.of(transaction));
        var messaging = mock(FirebaseMessaging.class);
        when(messaging.sendAsync(any(Message.class))).thenReturn(ApiFutures.immediateFuture("projects/synthetic/messages/1"));
        try (var firebase = mockStatic(FirebaseMessaging.class)) {
            firebase.when(FirebaseMessaging::getInstance).thenReturn(messaging);
            new PaymentPushSender(repository).sendPayment(2L, "synthetic-device", "FARE", "synthetic-reference", 1000);
        }
        var message = org.mockito.ArgumentCaptor.forClass(Message.class); verify(messaging).sendAsync(message.capture());
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                com.google.api.client.json.gson.GsonFactory.getDefaultInstance().toString(message.getValue()));
        assertThat(payload.at("/notification/body").asText()).isEqualTo("Open Premier to view your payment status.");
    }

    @Test void sdkMessageContainsAndroidDisplayPayloadAndStringData() throws Exception {
        var messaging = mock(FirebaseMessaging.class);
        when(messaging.sendAsync(any(Message.class))).thenAnswer(invocation -> {
            String json = com.google.api.client.json.gson.GsonFactory.getDefaultInstance()
                    .toString(invocation.getArgument(0, Message.class));
            var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            assertThat(payload.at("/notification/title").asText()).isEqualTo("Premier payment update");
            assertThat(payload.at("/android/notification/channel_id").asText()).isEqualTo("default");
            assertThat(payload.at("/data/type").asText()).isEqualTo("FARE");
            assertThat(payload.at("/data/reference").asText()).isEqualTo("synthetic-reference");
            return ApiFutures.immediateFuture("projects/synthetic/messages/1");
        });
        try (var firebase = mockStatic(FirebaseMessaging.class)) {
            firebase.when(FirebaseMessaging::getInstance).thenReturn(messaging);
            assertThat(new PaymentPushSender(mock(com.premier.repository.TransactionRepository.class)).send("synthetic-device", "FARE", "synthetic-reference", 1000))
                    .isEqualTo("projects/synthetic/messages/1");
        }
    }
    @Test void diagnosticRequiresBothDevProfileAndExplicitOptIn() {
        var context = new ApplicationContextRunner().withUserConfiguration(DevelopmentNotificationController.class)
                .withBean(PassengerFcmTokenRepository.class, () -> mock(PassengerFcmTokenRepository.class))
                .withBean(PaymentPushSender.class, () -> mock(PaymentPushSender.class));
        context.run(c -> assertThat(c).doesNotHaveBean(DevelopmentNotificationController.class));
        context.withPropertyValues("firebase.diagnostics.enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(DevelopmentNotificationController.class));
        context.withPropertyValues("spring.profiles.active=dev")
                .run(c -> assertThat(c).doesNotHaveBean(DevelopmentNotificationController.class));
        context.withPropertyValues("spring.profiles.active=dev", "firebase.diagnostics.enabled=true")
                .run(c -> assertThat(c).hasSingleBean(DevelopmentNotificationController.class));
    }
    @Test void diagnosticRejectsWrongAccountAndReportsProviderFailureTruthfully() throws Exception {
        var tokens = mock(PassengerFcmTokenRepository.class); var sender = mock(PaymentPushSender.class);
        var controller = new DevelopmentNotificationController(tokens, sender);
        var owner = new Passenger(); owner.setId(1L);
        var other = new Passenger(); other.setId(2L);
        var request = new FcmTokenRequest(); request.setFcmToken("synthetic-token");
        var registration = new PassengerFcmToken(); registration.setPassenger(owner);
        when(tokens.findByFcmToken("synthetic-token")).thenReturn(Optional.of(registration));
        assertThat(controller.test(null, request).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.test(other, request).getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(sender);
        when(sender.send(anyString(), anyString(), anyString(), anyLong())).thenThrow(new java.util.concurrent.TimeoutException());
        assertThat(controller.test(owner, request).getStatusCode().value()).isEqualTo(502);
        doReturn("projects/synthetic/messages/1").when(sender).send(anyString(), anyString(), anyString(), anyLong());
        assertThat(controller.test(owner, request).getBody().toString()).contains("FCM_ACCEPTED", "projects/synthetic/messages/1");
    }
}
