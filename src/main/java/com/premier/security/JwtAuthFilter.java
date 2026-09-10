package com.premier.security;

import com.premier.repository.PassengerRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.util.List;

@Component
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final PassengerRepository passengerRepository;

    public JwtAuthFilter(JwtUtil jwtUtil,
                         PassengerRepository passengerRepository) {
        this.jwtUtil = jwtUtil;
        this.passengerRepository = passengerRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();

        return path.equals("/api/passenger/auth/login")       ||
               path.equals("/api/passenger/auth/register")    ||
               path.equals("/api/passenger/auth/verify-totp") ||
               path.equals("/api/passenger/auth/totp/setup")  || 
               path.equals("/api/rfid/tap") ||
               path.equals("/api/rfid/nfc/tap") ||
               path.equals("/api/passenger/topup/webhook");
        
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        // Only process FULL tokens in this filter
        if (!jwtUtil.isFullToken(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Long passengerId = jwtUtil.extractPassengerId(token);
            var passenger = passengerRepository
                    .findById(passengerId)
                    .orElse(null);

            if (passenger != null && jwtUtil.isCurrentSession(token, passenger) &&
                SecurityContextHolder.getContext().getAuthentication() == null) {
                var auth = new UsernamePasswordAuthenticationToken(
                        passenger, null,
                        List.of(new SimpleGrantedAuthority("ROLE_PASSENGER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        } catch (Exception ex) {
            sendUnavailable(response, ex);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void sendUnavailable(HttpServletResponse response, Exception ex) throws IOException {
        String reference = RequestCorrelationFilter.reference();
        log.warn("Passenger authentication unavailable reference={} type={}", reference, ex.getClass().getSimpleName());
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"code\":\"SERVICE_UNAVAILABLE\",\"reference\":\""
                + reference + "\",\"message\":\"Authentication is temporarily unavailable.\"}");
    }
}
