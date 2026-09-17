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
            assertThat(new PaymentPushSender().send("synthetic-device", "FARE", "synthetic-reference", 1000))
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
