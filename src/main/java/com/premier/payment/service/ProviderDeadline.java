package com.premier.payment.service;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounds caller latency and concurrency even for a slow-streaming provider. Never executes in a DB transaction. */
@Component
public class ProviderDeadline {
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16), task -> { Thread t = new Thread(task, "payment-provider"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.AbortPolicy());
    public <T> T call(Supplier<T> operation) { return call(operation, 20000); }
    public <T> T call(Supplier<T> operation, long deadlineMillis) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Provider I/O is forbidden inside a transaction.");
        Future<T> future;
        try { future = workers.submit(operation::get); }
        catch (RejectedExecutionException full) { throw new ResourceAccessException("Provider capacity exhausted."); }
        try { return future.get(deadlineMillis, TimeUnit.MILLISECONDS); }
        catch (TimeoutException timeout) { future.cancel(true); throw new ResourceAccessException("Provider deadline exceeded."); }
        catch (InterruptedException interrupted) {
            future.cancel(true); Thread.currentThread().interrupt(); throw new ResourceAccessException("Provider request interrupted.");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new ResourceAccessException("Provider request failed.");
        }
    }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
