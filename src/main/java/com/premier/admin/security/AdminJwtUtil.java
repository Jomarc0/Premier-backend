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

@Slf4j
@Component
public class AdminJwtUtil {

    @Value("${jwt.admin-secret}") 
    private String secret;

    @Value("${jwt.admin-expiration:28800000}") 
    private long expiration;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(
            secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAdminToken(Long adminId, String role) {
        return Jwts.builder()
                .subject(String.valueOf(adminId))
                .claim("role", role)
                .claim("type", "ADMIN")
                .issuedAt(new Date())
                .expiration(new Date(
                    System.currentTimeMillis() + expiration))
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
            return "ADMIN".equals(type);
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
            log.warn("Admin JWT expired: {}", e.getMessage());
            return false;
        } catch (io.jsonwebtoken.JwtException e) {
            log.warn("Admin JWT invalid: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Unexpected admin JWT error: {}", e.getMessage());
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

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}