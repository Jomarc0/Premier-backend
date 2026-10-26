package com.premier.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Server-generated references cannot be forged by a caller or inject log content. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private static final Pattern SAFE_PAYMENT_CORRELATION_ID = Pattern.compile("[A-Za-z0-9_-]{12,80}");
    @Value("${app.deployment-version:unknown}")
    private String deploymentVersion;

    public static String reference() {
        String current = MDC.get("requestReference");
        return current == null ? UUID.randomUUID().toString() : current;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String previous = MDC.get("requestReference");
        String previousPaymentCorrelation = MDC.get("paymentCorrelationId");
        String reference = UUID.randomUUID().toString();
        String paymentCorrelationId = request.getHeader("X-Payment-Correlation-Id");
        if (paymentCorrelationId == null || !SAFE_PAYMENT_CORRELATION_ID.matcher(paymentCorrelationId).matches()) {
            paymentCorrelationId = "unavailable";
        }
        long started = System.nanoTime();
        MDC.put("requestReference", reference);
        response.setHeader("X-Request-Reference", reference);
        if (!"unavailable".equals(paymentCorrelationId)) {
            MDC.put("paymentCorrelationId", paymentCorrelationId);
            response.setHeader("X-Payment-Correlation-Id", paymentCorrelationId);
        }
        try { chain.doFilter(request, response); }
        finally {
            String path = request.getRequestURI();
            if (path != null && (path.equals("/api/rfid/tap") || path.equals("/api/rfid/qr/process")
                    || path.equals("/api/rfid/nfc/tap"))) {
                String instance = System.getenv("RENDER_INSTANCE_ID");
                if (!StringUtils.hasText(instance)) instance = System.getenv().getOrDefault("HOSTNAME", "local");
                long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                log.info("[PERF] stage=http_request_complete requestReference={} paymentCorrelationId={} endpoint={} status={} totalMs={} instance={} deployment={}",
                        reference, paymentCorrelationId, path, response.getStatus(), elapsedMs, instance, deploymentVersion);
            }
            if (previous == null) MDC.remove("requestReference");
            else MDC.put("requestReference", previous);
            if (previousPaymentCorrelation == null) MDC.remove("paymentCorrelationId");
            else MDC.put("paymentCorrelationId", previousPaymentCorrelation);
        }
    }
}
