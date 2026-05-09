package com.premier.admin.security;

import com.premier.admin.model.Admin;
import com.premier.admin.repository.AdminRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private final AdminJwtUtil    adminJwtUtil;
    private final AdminRepository adminRepository;

    public AdminAuthFilter(AdminJwtUtil adminJwtUtil,
                           AdminRepository adminRepository) {
        this.adminJwtUtil    = adminJwtUtil;
        this.adminRepository = adminRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/admin/auth/login")  ||
               path.startsWith("/api/admin/auth/")   ||
               path.startsWith("/api/passenger/")    ||
               path.equals("/api/rfid/tap")          ||
               path.startsWith("/api/driver/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getServletPath();
        log.info("AdminAuthFilter path={} method={}", path, request.getMethod());

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("No Bearer token on path={}", path);
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        log.info("Token present, first 20 chars: {}", token.substring(0, Math.min(20, token.length())));

        boolean isAdminToken = adminJwtUtil.isAdminToken(token);
        log.info("isAdminToken={}", isAdminToken);

        if (!isAdminToken) {
            log.warn("Not an admin token — skipping auth set for path={}", path);
            filterChain.doFilter(request, response);
            return;
        }

        boolean isValid = adminJwtUtil.isTokenValid(token);
        log.info("isTokenValid={}", isValid);

        if (!isValid) {
            sendUnauthorized(response, "Admin session expired. Please login again.");
            return;
        }

        Long adminId = adminJwtUtil.extractAdminId(token);
        log.info("👤 adminId={}", adminId);

        Admin admin = adminRepository.findById(adminId).orElse(null);

        if (admin == null) {
            log.warn("❌ Admin not found for id={}", adminId);
            sendUnauthorized(response, "Admin account not found.");
            return;
        }

        log.info("👤 Admin found: username={} role={} active={} locked={}",
            admin.getUsername(),
            admin.getRole().name(),
            admin.getActive(),
            admin.isLocked()
        );

        if (!admin.getActive()) {
            sendUnauthorized(response, "Admin account is disabled.");
            return;
        }

        if (admin.isLocked()) {
            sendUnauthorized(response, "Admin account is locked. Try again later.");
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String authority = admin.getRole().name(); // "SUPER_ADMIN" or "ADMIN"
            log.info("Setting authority: {}", authority);

            var auth = new UsernamePasswordAuthenticationToken(
                admin, null,
                List.of(new SimpleGrantedAuthority(authority))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
            log.info("SecurityContext set for {} with authority [{}]",
                admin.getUsername(), authority);
        } else {
            log.info("SecurityContext already set — skipping");
        }

        filterChain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletResponse response,
                                  String message) throws IOException {
        log.error("Sending 401: {}", message);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
            "{\"status\":\"error\",\"message\":\"" + message + "\"}");
    }
}