package com.premier.admin.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import com.premier.admin.model.Admin;
import com.premier.admin.model.AdminRole;
import com.premier.admin.repository.AdminRepository;
import lombok.RequiredArgsConstructor;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminJwtUtil {
    private final AdminRepository adminRepository;

    @Value("${jwt.admin-secret}") 
    private String secret;

    @Value("${jwt.admin-expiration:28800000}") 
    private long expiration;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(
            secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAdminToken(Long adminId, String role) {
        Admin admin = adminRepository.findById(adminId).orElseThrow();
        boolean enrollment = admin.getRole() != AdminRole.STAFF && !Boolean.TRUE.equals(admin.getIs2FaEnabled());
        return Jwts.builder()
                .subject(String.valueOf(adminId))
                .claim("role", role)
                .claim("type", enrollment ? "ADMIN_ENROLL" : "ADMIN")
                .claim("sv", admin.getSessionVersion())
                .issuedAt(new Date())
                .expiration(new Date(
                    System.currentTimeMillis() + (enrollment ? 300000 : expiration)))
                .signWith(getSigningKey())
                .compact();
    }

    public Long extractAdminId(String token) {
        return Long.parseLong(getClaims(token).getSubject());
    }

    public boolean isAdminToken(String token) {
        try {
            String type = getClaims(token)
                .get("type", String.class);
            return "ADMIN".equals(type) || "ADMIN_ENROLL".equals(type);
        } catch (Exception e) {
            return false; 
        }
    }

    public boolean isTokenValid(String token) {
        if (token == null || token.isBlank())
            return false;
        try {
            getClaims(token);
            return true;
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            log.debug("Expired admin JWT rejected");
            return false;
        } catch (io.jsonwebtoken.JwtException e) {
            log.debug("Invalid admin JWT rejected");
            return false;
        } catch (Exception e) {
            log.debug("Admin JWT could not be validated");
            return false;
        }
    }

    public String extractRole(String token) {
        try {
            return getClaims(token).get("role", String.class);
        } catch (Exception e) {
            return "";
        }
    }

    public boolean isEnrollmentToken(String token) { return "ADMIN_ENROLL".equals(getClaims(token).get("type", String.class)); }

    public boolean isCurrentSession(String token, Admin admin) {
        if (!isTokenValid(token) || admin == null) return false;
        Number version = getClaims(token).get("sv", Number.class);
        return (version == null ? 0L : version.longValue()) == admin.getSessionVersion();
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
