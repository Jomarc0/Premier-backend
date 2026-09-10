package com.premier.service;
import com.premier.payment.service.ProviderDeadline;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import static org.assertj.core.api.Assertions.*;
class ProviderDeadlineTest {
    @Test void stalledProviderIsBoundedAndInterrupted() throws Exception {
        ProviderDeadline deadline = new ProviderDeadline(); CountDownLatch interrupted = new CountDownLatch(1);
        try {
            assertThatThrownBy(() -> deadline.call(() -> {
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return "unknown";
            }, 100)).isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
            assertThat(interrupted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally { deadline.close(); }
    }
}
