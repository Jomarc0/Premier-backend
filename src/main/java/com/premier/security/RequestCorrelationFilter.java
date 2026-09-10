package com.premier.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

/** Server-generated references cannot be forged by a caller or inject log content. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    public static String reference() {
        String current = MDC.get("requestReference");
        return current == null ? UUID.randomUUID().toString() : current;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String previous = MDC.get("requestReference");
        String reference = UUID.randomUUID().toString();
        MDC.put("requestReference", reference);
        response.setHeader("X-Request-Reference", reference);
        try { chain.doFilter(request, response); }
        finally {
            if (previous == null) MDC.remove("requestReference");
            else MDC.put("requestReference", previous);
        }
    }
}
