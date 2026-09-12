package com.premier.service;

import com.premier.model.PassengerFcmToken;
import com.premier.payment.model.PaymentNotification;
import com.premier.payment.repository.PaymentNotificationRepository;
import com.premier.payment.service.*;
import com.premier.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentNotificationRegressionTest {
    private final PaymentNotificationRepository notices = mock(PaymentNotificationRepository.class);
    private final PassengerRepository passengers = mock(PassengerRepository.class);
    private final PassengerFcmTokenRepository tokens = mock(PassengerFcmTokenRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PaymentPushSender push = mock(PaymentPushSender.class);
    private final PaymentNotificationService service = new PaymentNotificationService(notices, passengers, tokens, transactions, push);
    private PaymentNotification notice;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        notice = new PaymentNotification(); notice.setId(1L); notice.setPassengerId(2L);
        notice.setKind("TOPUP"); notice.setReference("synthetic-reference"); notice.setDueAt(Instant.now().minusSeconds(1));
        when(notices.findTop25ByStatusAndDueAtBeforeOrderByDueAtAsc(eq("PENDING"), any())).thenReturn(List.of(notice));
        when(notices.lock(1L)).thenReturn(Optional.of(notice));
        when(passengers.findById(2L)).thenReturn(Optional.empty());
        when(tokens.findTop11ByPassengerIdOrderByUpdatedAtDesc(2L)).thenReturn(List.of(token("synthetic-token")));
    }
    private PassengerFcmToken token(String value) {
        var token = new PassengerFcmToken(); token.setFcmToken(value); return token;
    }
    @Test void twentyExpiredLeasesRequireReviewWithoutAnotherProviderCall() {
        notice.setAttempts(20);
        service.deliverPending();
        assertThat(notice.getStatus()).isEqualTo("REVIEW");
        assertThat(notice.getAttempts()).isEqualTo(20);
        verifyNoInteractions(push, passengers, tokens);
    }
    @Test void excessiveDestinationsRequireReviewWithoutPartialDelivery() {
        when(tokens.findTop11ByPassengerIdOrderByUpdatedAtDesc(2L)).thenReturn(
            IntStream.range(0, 11).mapToObj(i -> token("synthetic-" + i)).toList());
        service.deliverPending();
        assertThat(notice.getStatus()).isEqualTo("REVIEW");
        verifyNoInteractions(push);
    }
    @Test void staleWorkerCannotOverwriteNewerLease() throws Exception {
        var newer = new PaymentNotification(); newer.setAttempts(2); newer.setStatus("PENDING");
        when(notices.lock(1L)).thenReturn(Optional.of(notice), Optional.of(newer));
        service.deliverPending();
        assertThat(newer.getStatus()).isEqualTo("PENDING");
        assertThat(newer.getAttempts()).isEqualTo(2);
        verify(push).send(eq("synthetic-token"), eq("TOPUP"), eq("synthetic-reference"), longThat(ms -> ms > 0 && ms <= 10000));
    }
    @Test void providerFailureRetainsRetryableIntent() throws Exception {
        doThrow(new java.util.concurrent.TimeoutException()).when(push).send(anyString(), anyString(), anyString(), anyLong());
        service.deliverPending();
        assertThat(notice.getStatus()).isEqualTo("PENDING");
        assertThat(notice.getAttempts()).isEqualTo(1);
        assertThat(notice.getDueAt()).isAfter(Instant.now());
    }
    @Test void missingDeviceTokenRetainsRetryableIntent() {
        when(tokens.findTop11ByPassengerIdOrderByUpdatedAtDesc(2L)).thenReturn(List.of());
        service.deliverPending();
        assertThat(notice.getStatus()).isEqualTo("PENDING");
        assertThat(notice.getAttempts()).isEqualTo(1);
        assertThat(notice.getDueAt()).isAfter(Instant.now());
        verifyNoInteractions(push);
    }
    @Test void interruptionPreservesLeaseAndInterruptFlag() throws Exception {
        doThrow(new InterruptedException()).when(push).send(anyString(), anyString(), anyString(), anyLong());
        try {
            service.deliverPending();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(notice.getStatus()).isEqualTo("PENDING");
            assertThat(notice.getDueAt()).isAfter(Instant.now().plusSeconds(250));
        } finally { Thread.interrupted(); }
    }
}
