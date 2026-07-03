package com.premier.security;

import com.premier.device.security.DeviceContext;
import com.premier.device.security.DevicePrincipal;
import com.premier.device.service.DeviceService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    private final DeviceService deviceService;

    public DeviceAuthFilter(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || !requiresDeviceAuthentication(path);
    }

    private boolean requiresDeviceAuthentication(String path) {
        return path.equals("/api/rfid/tap")
                || path.equals("/api/rfid/qr/process")
                || path.equals("/api/rfid/nfc/tap")
                || path.equals("/api/rfid/driver/gps");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        try {
            DevicePrincipal principal = deviceService.authenticate(
                    request.getHeader("X-Device-Id"),
                    request.getHeader("X-Device-Token"));
            DeviceContext.set(principal);
            setAuthority("DEVICE_" + principal.deviceType().name());
            filterChain.doFilter(request, response);
        } catch (SecurityException e) {
            sendUnauthorized(response, e.getMessage());
        } finally {
            DeviceContext.clear();
        }
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
