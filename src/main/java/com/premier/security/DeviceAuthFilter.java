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
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;

@Component
@Slf4j
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
                || path.equals("/api/rfid/heartbeat")
                || path.equals("/api/rfid/qr/process")
                || path.equals("/api/rfid/nfc/tap")
                || path.equals("/api/rfid/gps")
                || path.equals("/api/rfid/registration/uid-request")
                || path.equals("/api/rfid/registration/uid-capture");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        DevicePrincipal principal;
        try {
            principal = deviceService.authenticate(
                    request.getHeader("X-Device-Id"),
                    request.getHeader("X-Device-Token"));
        } catch (SecurityException rejected) {
            sendUnauthorized(response);
            return;
        } catch (Exception ex) {
            sendUnavailable(response, ex);
            return;
        }
        try {
            DeviceContext.set(principal);
            setAuthority("DEVICE_" + principal.deviceType().name());
            filterChain.doFilter(request, response);
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

    private void sendUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"code\":\"DEVICE_REJECTED\",\"reference\":\""
                + RequestCorrelationFilter.reference() + "\",\"message\":\"Device authorization failed.\"}");
    }

    private void sendUnavailable(HttpServletResponse response, Exception ex) throws IOException {
        String reference = RequestCorrelationFilter.reference();
        log.warn("Device authentication unavailable reference={} type={}", reference, ex.getClass().getSimpleName());
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"code\":\"SERVICE_UNAVAILABLE\",\"reference\":\""
                + reference + "\",\"message\":\"Authentication is temporarily unavailable.\"}");
    }
}
