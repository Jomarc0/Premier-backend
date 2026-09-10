package com.premier.payment.service;
import com.premier.payment.model.PaymentNotification;
import com.premier.payment.repository.PaymentNotificationRepository;
import com.premier.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

/** Financial commit and delivery are independent. Leases permit recovery after worker/process failure. */
@Service @RequiredArgsConstructor @Slf4j
public class PaymentNotificationService {
    private final PaymentNotificationRepository notices;
    private final PassengerRepository passengers;
    private final PassengerFcmTokenRepository tokens;
    private final PlatformTransactionManager transactions;
    private final PaymentPushSender push;
    @Value("${payment.notifications.enabled:true}") private boolean enabled;
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Long passengerId, String reference, String kind) {
        PaymentNotification n = new PaymentNotification(); n.setPassengerId(passengerId); n.setReference(reference); n.setKind(kind);
        notices.save(n);
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
                Set<String> destinations = new HashSet<>();
                passengers.findById(claimed.getPassengerId()).map(p -> p.getFcmToken()).ifPresent(destinations::add);
                tokens.findTop11ByPassengerIdOrderByUpdatedAtDesc(claimed.getPassengerId()).forEach(t -> destinations.add(t.getFcmToken()));
                destinations.removeIf(t -> t == null || t.isBlank());
                if (destinations.size() > 10) {
                    review = true;
                    throw new IllegalStateException("Notification destination limit exceeded.");
                }
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                for (String destination : destinations) {
                    long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
                    push.send(destination, claimed.getKind(), claimed.getReference(), Math.min(10000, remaining));
                }
                sent = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return; // Leave the leased intent durable for the next worker.
            } catch (Exception failure) { log.warn("PAYMENT_NOTIFICATION_RETRY id={} type={}", claimed.getId(), failure.getClass().getSimpleName()); }
            final boolean delivered = sent;
            final boolean requiresReview = review;
            tx.executeWithoutResult(s -> {
                var n = notices.lock(claimed.getId()).orElseThrow();
                // A timed-out worker must not overwrite a newer worker's lease or result.
                if (!"PENDING".equals(n.getStatus()) || n.getAttempts() != claimed.getAttempts()) return;
                n.setStatus(delivered ? "DELIVERED" : requiresReview || n.getAttempts() >= 20 ? "REVIEW" : "PENDING");
                n.setDueAt(Instant.now().plusSeconds(Math.min(900, 10L << Math.min(n.getAttempts(), 6))));
            });
        }
    }
}
