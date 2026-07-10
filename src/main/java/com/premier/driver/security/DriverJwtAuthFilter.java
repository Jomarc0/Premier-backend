package com.premier.driver.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class DriverJwtAuthFilter extends OncePerRequestFilter {

    private final DriverJwtUtil driverJwtUtil;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/driver/login")      ||
               path.equals("/api/driver/setup")      ||
               path.equals("/api/driver/buses")      ||
               path.equals("/api/driver/vehicles")   ||
               path.equals("/api/driver/drivers")    ||
               path.startsWith("/api/staff/")        ||
               path.startsWith("/api/public/")       ||
               path.startsWith("/ws")				 ||
               path.equals("/api/rfid/tap"); 
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null
                || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        // Only process DRIVER tokens; passenger tokens are handled
        // by JwtAuthFilter downstream
        if (!driverJwtUtil.isDriverToken(token)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            if (driverJwtUtil.validateToken(token) &&
                SecurityContextHolder.getContext()
                    .getAuthentication() == null) {

                String plateNumber =
                    driverJwtUtil.extractPlateNumber(token);

                UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                        plateNumber,
                        null,
                        List.of(new SimpleGrantedAuthority(
                            "ROLE_DRIVER"))
                    );

                SecurityContextHolder
                    .getContext()
                    .setAuthentication(auth);
            }
        } catch (Exception ex) {
            log.warn("Driver JWT filter error: {}",
                ex.getMessage());
        }

        chain.doFilter(request, response);
    }
}
