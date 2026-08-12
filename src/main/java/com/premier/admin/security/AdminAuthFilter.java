package com.premier.admin.security;

import com.premier.admin.model.Admin;
import com.premier.admin.repository.AdminRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@Slf4j
public class AdminAuthFilter extends OncePerRequestFilter {

    private final AdminJwtUtil adminJwtUtil;
    private final AdminRepository adminRepository;

    public AdminAuthFilter(AdminJwtUtil adminJwtUtil,
                           AdminRepository adminRepository) {
        this.adminJwtUtil = adminJwtUtil;
        this.adminRepository = adminRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/admin/auth/login")
                || path.startsWith("/api/passenger/")
                || path.startsWith("/api/public/")
                || path.startsWith("/api/rfid/");
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
        if (!adminJwtUtil.isAdminToken(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!adminJwtUtil.isTokenValid(token)) {
            sendUnauthorized(response, "Admin session expired. Please login again.");
            return;
        }

        try {
            Long adminId = adminJwtUtil.extractAdminId(token);
            Admin admin = adminRepository.findById(adminId).orElse(null);

            if (admin == null) {
                sendUnauthorized(response, "Admin account not found.");
                return;
            }

            if (!admin.getActive()) {
                sendUnauthorized(response, "Admin account is disabled.");
                return;
            }

            if (admin.isLocked()) {
                sendUnauthorized(response, "Admin account is locked. Try again later.");
                return;
            }

            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                String authority = admin.getRole().name();
                var auth = new UsernamePasswordAuthenticationToken(
                        admin,
                        null,
                        List.of(new SimpleGrantedAuthority(authority)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }

            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            log.error("AdminAuthFilter failed for {} {}", request.getMethod(), request.getServletPath(), ex);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"Unable to complete the admin request.\"}");
        }
    }

    private void sendUnauthorized(HttpServletResponse response,
                                  String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"" + message + "\"}");
    }
}
