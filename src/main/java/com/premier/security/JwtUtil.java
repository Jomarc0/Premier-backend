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
import com.premier.model.Passenger;
import com.premier.repository.PassengerRepository;
import lombok.RequiredArgsConstructor;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {
    private final PassengerRepository passengerRepository;

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
        Passenger passenger = passengerRepository.findById(passengerId).orElseThrow();
        return Jwts.builder().subject(String.valueOf(passengerId)).claim("type", "FULL")
                .claim("sv", passenger.getSessionVersion()).id(java.util.UUID.randomUUID().toString())
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey()).compact();
    }

    public String generateChallenge(Long passengerId, String purpose, String challengeId, java.time.Instant expiresAt) {
        return Jwts.builder().subject(String.valueOf(passengerId)).claim("type", purpose)
                .id(challengeId).issuedAt(new Date()).expiration(Date.from(expiresAt))
                .signWith(getSigningKey()).compact();
    }

    public String challengeId(String token) { return getClaims(token).getId(); }

    public boolean isCurrentSession(String token, Passenger passenger) {
        if (!isFullToken(token) || passenger == null || passenger.getStatus() != com.premier.model.PassengerStatus.ACTIVE
                || !Boolean.TRUE.equals(passenger.getIs2FaEnabled())) return false;
        Number version = getClaims(token).get("sv", Number.class);
        return (version == null ? 0L : version.longValue()) == passenger.getSessionVersion();
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
            log.debug("Expired JWT rejected");
            return false;
        } catch (io.jsonwebtoken.JwtException e) {
            log.debug("Invalid JWT rejected");
            return false;
        } catch (Exception e) {
            log.debug("JWT could not be validated");
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
