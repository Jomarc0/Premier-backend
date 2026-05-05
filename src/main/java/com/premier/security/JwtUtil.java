package com.premier.security;

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
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration:86400000}")
    private long expiration;

    @Value("${jwt.temp-expiration:300000}")
    private long tempExpiration;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(
            secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateFullToken(Long passengerId) {
        return buildToken(passengerId, expiration, "FULL");
    }

    public String generateTempToken(Long passengerId) {
        return buildToken(passengerId, tempExpiration, "TEMP");
    }

    // because .claims(Map) in JJWT 0.12.x resets ALL claims
    // including subject, causing isTempToken() to always fail
    private String buildToken(Long passengerId,
                               long expiry,
                               String type) {
        return Jwts.builder()
                .subject(String.valueOf(passengerId))
                .claim("type", type)
                .issuedAt(new Date())
                .expiration(new Date(
                    System.currentTimeMillis() + expiry))
                .signWith(getSigningKey())
                .compact();
    }

    public Long extractPassengerId(String token) {
        return Long.parseLong(
            getClaims(token).getSubject());
    }

    public String extractTokenType(String token) {
        String type = getClaims(token)
            .get("type", String.class);
        return type != null ? type : "";
    }

    public boolean isTokenValid(String token) {
        if (token == null || token.isBlank())
            return false;
        try {
            getClaims(token);
            return true;
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            log.warn("JWT expired: {}", e.getMessage());
            return false;
        } catch (io.jsonwebtoken.JwtException e) {
            log.warn("JWT invalid: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Unexpected JWT error: {}",
                e.getMessage());
            return false;
        }
    }

    public boolean isFullToken(String token) {
        return isTokenValid(token) &&
               "FULL".equals(extractTokenType(token));
    }

    public boolean isTempToken(String token) {
        return isTokenValid(token) &&
               "TEMP".equals(extractTokenType(token));
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}