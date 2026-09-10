package com.premier.payment.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.*;

/** Best-effort diagnostic attempts, never a financial ledger or a settlement queue. */
@Component @Slf4j
public class PaymentFailureRecorder {
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000), runnable -> {
                Thread thread = new Thread(runnable, "payment-failure-audit");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public void afterTransaction(Runnable record) {
        Runnable submit = () -> {
            try {
                executor.execute(() -> {
                    try { record.run(); }
                    catch (RuntimeException ex) { log.error("PAYMENT_FAILURE_AUDIT_UNAVAILABLE type={}", ex.getClass().getSimpleName()); }
                });
            } catch (RejectedExecutionException ex) { log.error("PAYMENT_FAILURE_AUDIT_QUEUE_FULL"); }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { submit.run(); }
            });
        } else { submit.run(); }
    }

    @PreDestroy public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) log.error("PAYMENT_FAILURE_AUDIT_SHUTDOWN_PENDING");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
