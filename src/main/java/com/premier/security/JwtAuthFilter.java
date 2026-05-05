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
import java.io.IOException;
import java.util.List;

@Component
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

        Long passengerId = jwtUtil.extractPassengerId(token);
        var passenger = passengerRepository
                .findById(passengerId)
                .orElse(null);

        if (passenger != null &&
            SecurityContextHolder.getContext().getAuthentication() == null) {
            var auth = new UsernamePasswordAuthenticationToken(
                    passenger, null,
                    List.of(new SimpleGrantedAuthority("ROLE_PASSENGER")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        filterChain.doFilter(request, response);
    }
}