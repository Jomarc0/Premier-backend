package com.premier.driver.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
@Slf4j
public class DriverJwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration:86400000}")
    private long expiration;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(
            secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String plateNumber, Long shiftId) {
        return Jwts.builder()
                .subject(plateNumber)
                .claim("shiftId", shiftId)
                .claim("type", "DRIVER")
                .issuedAt(new Date())
                .expiration(new Date(
                    System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    public String extractPlateNumber(String token) {
        return getClaims(token).getSubject();
    }

    public Long extractShiftId(String token) {
        return getClaims(token).get("shiftId", Long.class);
    }

    public boolean isDriverToken(String token) {
        try {
            String type = getClaims(token)
                .get("type", String.class);
            return "DRIVER".equals(type);
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (ExpiredJwtException ex) {
            log.warn("Driver JWT expired: {}", ex.getMessage());
            return false;
        } catch (JwtException ex) {
            log.warn("Driver JWT invalid: {}", ex.getMessage());
            return false;
        } catch (Exception ex) {
            log.error("Driver JWT error: {}", ex.getMessage());
            return false;
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