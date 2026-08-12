package com.premier.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local safety net for sensitive routes. Cloudflare/Redis remains required for distributed enforcement. */
@Component
public class SecurityRateLimitFilter extends OncePerRequestFilter {
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) return true;
        String path = request.getServletPath();
        return !path.equals("/api/passenger/auth/register")
                && !path.equals("/api/passenger/auth/login")
                && !path.equals("/api/passenger/auth/verify-totp")
                && !path.equals("/api/passenger/support-tickets")
                && !path.equals("/api/passenger/card/report-lost")
                && !path.equals("/api/rfid/tap")
                && !path.equals("/api/rfid/qr/process")
                && !path.equals("/api/rfid/nfc/tap")
                && !path.equals("/api/rfid/gps");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        String identity = path.startsWith("/api/rfid/")
                ? safe(request.getHeader("X-Device-Id"), request.getRemoteAddr())
                : request.getRemoteAddr();
        Bucket bucket = buckets.computeIfAbsent(path + ':' + identity, ignored -> newBucket(path));
        if (!bucket.tryConsume(1)) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"message\":\"Too many requests. Please retry later.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private Bucket newBucket(String path) {
        long capacity = path.equals("/api/passenger/auth/register") || path.equals("/api/passenger/support-tickets")
                || path.equals("/api/passenger/card/report-lost") ? 5
                : path.equals("/api/passenger/auth/verify-totp") ? 10 : path.startsWith("/api/rfid/") ? 120 : 20;
        Duration period = path.equals("/api/passenger/auth/register") || path.equals("/api/passenger/support-tickets")
                || path.equals("/api/passenger/card/report-lost")
                ? Duration.ofHours(1) : Duration.ofMinutes(1);
        return Bucket.builder().addLimit(Bandwidth.classic(capacity, Refill.greedy(capacity, period))).build();
    }

    private String safe(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred.trim();
    }
}
