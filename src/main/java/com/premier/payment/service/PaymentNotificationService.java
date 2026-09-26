package com.premier.payment.service;
import com.premier.payment.model.PaymentNotification;
import com.premier.payment.repository.PaymentNotificationRepository;
import com.premier.model.Passenger;
import com.premier.repository.*;
import com.premier.response.*;
import com.premier.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/** Financial commit and delivery are independent. Leases permit recovery after worker/process failure. */
@Service @RequiredArgsConstructor @Slf4j
public class PaymentNotificationService {
    private final PaymentNotificationRepository notices;
    private final PassengerRepository passengers;
    private final PassengerFcmTokenRepository tokens;
    private final TransactionRepository transactionRepository;
    private final PlatformTransactionManager transactions;
    private final PaymentPushSender push;
    @Value("${payment.notifications.enabled:true}") private boolean enabled;
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Long passengerId, String reference, String kind) {
        PaymentNotification n = new PaymentNotification(); n.setPassengerId(passengerId); n.setReference(reference); n.setKind(kind);
        notices.save(n);
        log.debug("[FCM] stage=ENQUEUED passenger={} reference={} kind={} notification={}", passengerId, reference, kind, n.getId());
    }

    @Transactional(readOnly = true)
    public ApiResponse<NotificationHistoryResponse> history(Passenger passenger, int page, int size) {
        if (page < 0 || size < 1 || size > 50)
            throw new ClientException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be non-negative and size must be between 1 and 50.");
        log.info("[Notifications] Fetching history passenger={} page={} size={}", passenger.getId(), page, size);
        var notifications = notices.findByPassengerIdOrderByCreatedAtDesc(passenger.getId(), PageRequest.of(page, size));
        var content = notifications.getContent().stream().map(this::response).toList();
        return ApiResponse.success("Notifications fetched.", NotificationHistoryResponse.builder()
                .content(content)
                .page(notifications.getNumber())
                .size(notifications.getSize())
                .totalElements(notifications.getTotalElements())
                .totalPages(notifications.getTotalPages())
                .unreadCount(notices.countByPassengerIdAndReadAtIsNull(passenger.getId()))
                .build());
    }

    @Transactional
    public ApiResponse<NotificationResponse> markRead(Passenger passenger, Long id) {
        var notification = notices.findByIdAndPassengerId(id, passenger.getId()).orElseThrow(() ->
                new ClientException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification not found."));
        if (notification.getReadAt() == null) notification.setReadAt(Instant.now());
        return ApiResponse.success("Notification marked as read.", response(notification));
    }

    @Transactional
    public ApiResponse<Map<String, Object>> markAllRead(Passenger passenger) {
        int updated = notices.markAllRead(passenger.getId(), Instant.now());
        return ApiResponse.success("Notifications marked as read.", Map.of("updated", updated, "unreadCount", 0));
    }

    private NotificationResponse response(PaymentNotification notification) {
        var transaction = transactionRepository.findByReferenceNumberAndPassengerId(
                notification.getReference(), notification.getPassengerId());
        String title;
        String message;
        if ("TOPUP".equals(notification.getKind())) {
            title = "Top-up successful";
            message = transaction.map(value -> money(value.getAmount()) + " added. New balance: "
                    + money(value.getBalanceAfter()) + ".").orElse("Your card balance was topped up successfully.");
        } else {
            title = "Fare deducted";
            message = transaction.map(value -> money(value.getAmount()) + " paid. Remaining balance: "
                    + money(value.getBalanceAfter()) + ".").orElse("Your fare payment was completed successfully.");
        }
        return NotificationResponse.builder()
                .id(notification.getId())
                .title(title)
                .message(message)
                .type(notification.getKind())
                .reference(notification.getReference())
                .read(notification.getReadAt() != null)
                .createdAt(notification.getCreatedAt())
                .build();
    }

    private String money(java.math.BigDecimal amount) {
        return amount == null ? "\u20b10.00" : "\u20b1" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
    @Scheduled(fixedDelayString = "${payment.notifications.interval-ms:15000}", initialDelay = 15000)
    public void deliverPending() {
        if (!enabled) return;
        for (var candidate : notices.findTop25ByStatusAndDueAtBeforeOrderByDueAtAsc("PENDING", Instant.now())) {
            var tx = new TransactionTemplate(transactions);
            PaymentNotification claimed = tx.execute(s -> {
                var n = notices.lock(candidate.getId()).orElseThrow();
                if (!"PENDING".equals(n.getStatus()) || n.getDueAt().isAfter(Instant.now())) return null;
                // Expired leases consume an attempt even if the previous process crashed.
                if (n.getAttempts() >= 20) { n.setStatus("REVIEW"); return null; }
                n.setAttempts(n.getAttempts() + 1); n.setDueAt(Instant.now().plusSeconds(300)); return n;
            });
            if (claimed == null) continue;
            boolean sent = false;
            boolean review = false;
            try {
                Set<String> destinations = new LinkedHashSet<>();
                tokens.findTop11ByPassengerIdOrderByUpdatedAtDesc(claimed.getPassengerId()).forEach(t -> destinations.add(t.getFcmToken()));
                passengers.findById(claimed.getPassengerId()).map(p -> p.getFcmToken())
                    .filter(token -> tokens.findByFcmToken(token)
                        .map(registered -> registered.getPassenger().getId().equals(claimed.getPassengerId())).orElse(true))
                    .ifPresent(destinations::add);
                destinations.removeIf(t -> t == null || t.isBlank());
                log.debug("[FCM] stage=LOOKUP notification={} passenger={} reference={} kind={} destinations={}",
                        claimed.getId(), claimed.getPassengerId(), claimed.getReference(), claimed.getKind(), destinations.size());
                if (destinations.isEmpty()) {
                    log.debug("[FCM] stage=SKIPPED notification={} reason=NO_REGISTRATION", claimed.getId());
                    throw new IllegalStateException("No registered notification destination.");
                }
                if (destinations.size() > 10) {
                    log.warn("[FCM] stage=SKIPPED notification={} reason=DESTINATION_LIMIT", claimed.getId());
                    review = true;
                    throw new IllegalStateException("Notification destination limit exceeded.");
                }
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                boolean retryableFailure = false;
                for (String destination : destinations) {
                    long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) { retryableFailure = true; break; }
                    log.debug("[FCM] stage=SEND notification={} passenger={} reference={} kind={} attempt={}",
                            claimed.getId(), claimed.getPassengerId(), claimed.getReference(), claimed.getKind(), claimed.getAttempts());
                    try {
                        push.sendPayment(claimed.getPassengerId(), destination, claimed.getKind(), claimed.getReference(), Math.min(10000, remaining));
                        sent = true;
                    } catch (InterruptedException interrupted) {
                        throw interrupted;
                    } catch (Exception failure) {
                        log.warn("[FCM] result=FAILED notification={} passenger={} reference={} kind={} httpStatus={} code={}",
                                claimed.getId(), claimed.getPassengerId(), claimed.getReference(), claimed.getKind(),
                                FcmDiagnostics.httpStatus(failure), FcmDiagnostics.code(failure));
                        if (FcmDiagnostics.unregistered(failure)) {
                            // Conditional delete cannot remove a registration reassigned to another account.
                            tx.executeWithoutResult(s -> {
                                tokens.deleteOwnedToken(claimed.getPassengerId(), destination);
                                passengers.clearLegacyFcmToken(destination);
                            });
                        } else {
                            retryableFailure = true;
                        }
                    }
                }
                sent = sent && !retryableFailure;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return; // Leave the leased intent durable for the next worker.
            } catch (Exception failure) {
                sent = false;
                log.warn("PAYMENT_NOTIFICATION_RETRY id={} passenger={} reference={} kind={} code={}",
                        claimed.getId(), claimed.getPassengerId(), claimed.getReference(), claimed.getKind(), FcmDiagnostics.code(failure));
            }
            final boolean delivered = sent;
            final boolean requiresReview = review;
            tx.executeWithoutResult(s -> {
                var n = notices.lock(claimed.getId()).orElseThrow();
                // A timed-out worker must not overwrite a newer worker's lease or result.
                if (!"PENDING".equals(n.getStatus()) || n.getAttempts() != claimed.getAttempts()) return;
                n.setStatus(delivered ? "DELIVERED" : requiresReview || n.getAttempts() >= 20 ? "REVIEW" : "PENDING");
                log.debug("[FCM] stage=RESULT notification={} passenger={} reference={} status={}",
                        n.getId(), n.getPassengerId(), n.getReference(), n.getStatus());
                n.setDueAt(Instant.now().plusSeconds(Math.min(900, 10L << Math.min(n.getAttempts(), 6))));
            });
        }
    }
}
