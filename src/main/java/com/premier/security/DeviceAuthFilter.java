package com.premier.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class DeviceAuthFilter extends OncePerRequestFilter {

    @Value("${premier.security.rfid-terminal-token:}")
    private String rfidTerminalToken;

    @Value("${premier.security.driver-device-token:}")
    private String driverDeviceToken;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || !path.startsWith("/api/rfid/")
                && !path.equals("/api/driver/login")
                && !path.equals("/api/driver/location")
                && !path.equals("/api/driver/gps");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getServletPath();

        if (path.startsWith("/api/rfid/")) {
            if (!matchesToken(request.getHeader("X-Terminal-Token"), rfidTerminalToken)) {
                sendUnauthorized(response, "RFID terminal authentication required.");
                return;
            }
            setAuthority("RFID_TERMINAL");
        } else if (path.equals("/api/driver/login")) {
            if (!matchesToken(request.getHeader("X-Driver-Device-Token"), driverDeviceToken)) {
                sendUnauthorized(response, "Driver device authentication required.");
                return;
            }
        } else if (path.equals("/api/driver/location") || path.equals("/api/driver/gps")) {
            String bearer = request.getHeader("Authorization");
            if (bearer == null || !bearer.startsWith("Bearer ")) {
                if (!matchesToken(request.getHeader("X-Driver-Device-Token"), driverDeviceToken)) {
                    sendUnauthorized(response, "GPS device authentication required.");
                    return;
                }
                setAuthority("DEVICE");
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean matchesToken(String provided, String expected) {
        return expected != null
                && !expected.isBlank()
                && provided != null
                && provided.equals(expected);
    }

    private void setAuthority(String authority) {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            authority,
                            null,
                            List.of(new SimpleGrantedAuthority(authority))));
        }
    }

    private void sendUnauthorized(HttpServletResponse response,
                                  String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
    }
}
