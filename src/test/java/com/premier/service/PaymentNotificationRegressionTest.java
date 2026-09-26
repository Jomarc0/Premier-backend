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
    private final TransactionRepository ledger = mock(TransactionRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PaymentPushSender push = mock(PaymentPushSender.class);
    private final PaymentNotificationService service = new PaymentNotificationService(notices, passengers, tokens, ledger, transactions, push);
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
        verify(push).sendPayment(eq(2L), eq("synthetic-token"), eq("TOPUP"), eq("synthetic-reference"), longThat(ms -> ms > 0 && ms <= 10000));
    }
    @Test void providerFailureRetainsRetryableIntent() throws Exception {
        doThrow(new java.util.concurrent.TimeoutException()).when(push).sendPayment(eq(2L), anyString(), anyString(), anyString(), anyLong());
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
    @Test void unregisteredDestinationMustNotBlockCurrentPhone() throws Exception {
        // Reproduce the old HashSet iteration order so the stale destination is first.
        var ordered = new ArrayList<>(new HashSet<>(List.of("synthetic-old", "synthetic-current")));
        String stale = ordered.get(0), current = ordered.get(1);
        when(tokens.findTop11ByPassengerIdOrderByUpdatedAtDesc(2L)).thenReturn(List.of(token(stale), token(current)));
        var unregistered = mock(com.google.firebase.messaging.FirebaseMessagingException.class);
        when(unregistered.getMessagingErrorCode()).thenReturn(com.google.firebase.messaging.MessagingErrorCode.UNREGISTERED);
        doThrow(new java.util.concurrent.ExecutionException(unregistered)).when(push)
            .sendPayment(eq(2L), eq(stale), anyString(), anyString(), anyLong());
        service.deliverPending();
        verify(push).sendPayment(eq(2L), eq(current), eq("TOPUP"), eq("synthetic-reference"), anyLong());
        assertThat(notice.getStatus()).isEqualTo("DELIVERED");
        verify(tokens).deleteOwnedToken(2L, stale);
        verify(passengers).clearLegacyFcmToken(stale);
    }
    @Test void temporaryFailureStillAttemptsOtherDevicesWithoutDeletingTokens() throws Exception {
        when(tokens.findTop11ByPassengerIdOrderByUpdatedAtDesc(2L)).thenReturn(List.of(token("synthetic-failed"), token("synthetic-good")));
        doThrow(new java.util.concurrent.TimeoutException()).when(push).sendPayment(eq(2L), eq("synthetic-failed"), anyString(), anyString(), anyLong());
        service.deliverPending();
        verify(push).sendPayment(eq(2L), eq("synthetic-good"), anyString(), anyString(), anyLong());
        verify(tokens, never()).deleteOwnedToken(anyLong(), anyString());
        assertThat(notice.getStatus()).isEqualTo("PENDING");
    }
    @Test void invalidArgumentIsNotProofOfAnExpiredToken() throws Exception {
        var failure = mock(com.google.firebase.messaging.FirebaseMessagingException.class);
        when(failure.getMessagingErrorCode()).thenReturn(com.google.firebase.messaging.MessagingErrorCode.INVALID_ARGUMENT);
        doThrow(new java.util.concurrent.ExecutionException(failure)).when(push).sendPayment(eq(2L), anyString(), anyString(), anyString(), anyLong());
        service.deliverPending();
        verify(tokens, never()).deleteOwnedToken(anyLong(), anyString());
        assertThat(notice.getStatus()).isEqualTo("PENDING");
    }
    @Test void sameTokenInLegacyAndDeviceTableIsOnlySentOnce() throws Exception {
        var passenger = new com.premier.model.Passenger(); passenger.setId(2L); passenger.setFcmToken("synthetic-token");
        when(passengers.findById(2L)).thenReturn(Optional.of(passenger));
        service.deliverPending();
        verify(push, times(1)).sendPayment(eq(2L), eq("synthetic-token"), anyString(), anyString(), anyLong());
        assertThat(notice.getStatus()).isEqualTo("DELIVERED");
    }
    @Test void legacyTokenReassignedToAnotherPassengerMustNotReceivePayment() throws Exception {
        var original = new com.premier.model.Passenger(); original.setId(2L); original.setFcmToken("synthetic-shared");
        var newOwner = new com.premier.model.Passenger(); newOwner.setId(3L);
        var reassigned = token("synthetic-shared"); reassigned.setPassenger(newOwner);
        when(passengers.findById(2L)).thenReturn(Optional.of(original));
        when(tokens.findByFcmToken("synthetic-shared")).thenReturn(Optional.of(reassigned));
        service.deliverPending();
        verify(push, never()).sendPayment(eq(2L), eq("synthetic-shared"), anyString(), anyString(), anyLong());
        verify(push).sendPayment(eq(2L), eq("synthetic-token"), anyString(), anyString(), anyLong());
    }
    @Test void interruptionPreservesLeaseAndInterruptFlag() throws Exception {
        doThrow(new InterruptedException()).when(push).sendPayment(eq(2L), anyString(), anyString(), anyString(), anyLong());
        try {
            service.deliverPending();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(notice.getStatus()).isEqualTo("PENDING");
            assertThat(notice.getDueAt()).isAfter(Instant.now().plusSeconds(250));
        } finally { Thread.interrupted(); }
    }
}
