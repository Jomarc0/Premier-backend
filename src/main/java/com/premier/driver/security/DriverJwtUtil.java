package com.premier.driver.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

@Slf4j
@Component
public class DriverJwtUtil {
    @Value("${jwt.driver-secret:${jwt.admin-secret}}")
    private String secret;

    @Value("${jwt.driver-expiration:43200000}")
    private long expiration;

    private SecretKey key() { return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); }

    public String generate(Long driverId, Long shiftId, Long vehicleId, String plateNumber, String driverName) {
        long ttl = Math.min(expiration, Duration.ofHours(12).toMillis());
        return Jwts.builder().subject(String.valueOf(driverId)).claim("type", "DRIVER")
                .claim("shiftId", shiftId).claim("vehicleId", vehicleId).claim("plateNumber", plateNumber)
                .claim("driverName", driverName).issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttl)).signWith(key()).compact();
    }

    public boolean isDriverToken(String token) {
        try { return "DRIVER".equals(claims(token).get("type", String.class)); }
        catch (Exception e) { return false; }
    }

    public DriverPrincipal principal(String token) {
        Claims c = claims(token);
        return new DriverPrincipal(Long.parseLong(c.getSubject()), number(c, "shiftId"), number(c, "vehicleId"),
                c.get("plateNumber", String.class), c.get("driverName", String.class));
    }

    private Long number(Claims c, String key) {
        Number n = c.get(key, Number.class);
        return n == null ? null : n.longValue();
    }

    private Claims claims(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }
}
