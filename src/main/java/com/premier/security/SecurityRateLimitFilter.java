package com.premier.security;

import io.github.bucket4j.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Bounded per-instance safety net. Edge/distributed limits must also be configured. */
@Component
public class SecurityRateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_KEYS = 10000;
    private static final long TTL = Duration.ofHours(2).toNanos();
    private final Map<String, Entry> buckets = new HashMap<>();
    private long nextCleanup;
    private record Entry(Bucket bucket, long touched) {}
    record Policy(String group, long capacity, Duration period) {}

    static Policy policy(HttpServletRequest request) {
        String path = request.getServletPath();
        String method = request.getMethod();
        if (method.equals("OPTIONS") || path.equals("/api/passenger/topup/webhook")) return null;
        if (path.equals("/api/passenger/auth/register") || path.equals("/api/passenger/support-tickets") && method.equals("POST")
                || path.equals("/api/passenger/card/report-lost")) return new Policy("support-request", 5, Duration.ofHours(1));
        if (path.equals("/api/passenger/chat/message")) return new Policy("chat",20,Duration.ofMinutes(1));
        if (path.startsWith("/api/passenger/auth/") || path.startsWith("/api/admin/auth/"))
            return new Policy("authentication", 20, Duration.ofMinutes(1));
        if (path.startsWith("/api/rfid/")) return new Policy("device", 300, Duration.ofMinutes(1));
        if (path.startsWith("/api/passenger/")) return new Policy("passenger", 120, Duration.ofMinutes(1));
        if (path.startsWith("/api/admin/") || path.startsWith("/api/staff/") || path.startsWith("/api/driver/"))
            return new Policy(method.equals("GET") ? "operations-read" : "operations-write", method.equals("GET") ? 300 : 60, Duration.ofMinutes(1));
        return null;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return policy(request) == null; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Only the transport peer is trusted here. Spoofed device/forwarding headers cannot create a fresh identity.
        if (!consume("peer:" + request.getRemoteAddr(), policy(request))) { reject(response); return; }
        chain.doFilter(request, response);
    }
    synchronized boolean consume(String identity, Policy policy) {
        if (policy == null) return true;
        long now = System.nanoTime();
        if (now >= nextCleanup) {
            buckets.entrySet().removeIf(e -> now - e.getValue().touched() >= TTL);
            nextCleanup = now + Duration.ofMinutes(1).toNanos();
        }
        String key = policy.group() + ':' + identity;
        Entry entry = buckets.get(key);
        if (entry == null) {
            // Do not evict active limits: flooding unique identities must not reset a victim's budget.
            if (buckets.size() >= MAX_KEYS) return false;
            entry = new Entry(Bucket.builder().addLimit(Bandwidth.classic(policy.capacity(),
                    Refill.greedy(policy.capacity(), policy.period()))).build(), now);
        }
        buckets.put(key, new Entry(entry.bucket(), now));
        return entry.bucket().tryConsume(1);
    }
    static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests. Please retry later.\"}");
    }
}
