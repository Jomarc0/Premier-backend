package com.premier.service;

import com.premier.exception.InvalidBiometricTokenException;
import com.premier.model.BiometricRefreshToken;
import com.premier.model.Passenger;
import com.premier.model.PassengerStatus;
import com.premier.repository.BiometricRefreshTokenRepository;
import com.premier.response.ApiResponse;
import com.premier.response.AuthResponse;
import com.premier.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class BiometricAuthService {

    private final BiometricRefreshTokenRepository tokenRepository;
    private final JwtUtil jwtUtil;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${biometric.refresh-expiration-days:30}")
    private long refreshExpirationDays;

    @Transactional
    public ApiResponse<AuthResponse> enroll(Passenger passenger, String deviceId) {
        requireActivePassenger(passenger);
        String normalizedDeviceId = normalizeDeviceId(deviceId);
        Instant now = Instant.now();
        tokenRepository.revokeActiveForDevice(passenger.getId(), normalizedDeviceId, now);
        IssuedToken issued = prepare(passenger, normalizedDeviceId, now);
        tokenRepository.save(issued.entity());
        return ApiResponse.success("Biometric login enabled.", response(passenger, issued));
    }

    @Transactional
    public ApiResponse<AuthResponse> refresh(String rawToken, String deviceId) {
        String normalizedDeviceId = normalizeDeviceId(deviceId);
        BiometricRefreshToken stored = tokenRepository.findLockedByTokenHash(hash(rawToken))
                .orElseThrow(this::invalidToken);
        Instant now = Instant.now();

        if (!stored.isUsableAt(now)
                || !MessageDigest.isEqual(
                        stored.getDeviceId().getBytes(StandardCharsets.UTF_8),
                        normalizedDeviceId.getBytes(StandardCharsets.UTF_8))) {
            throw invalidToken();
        }

        Passenger passenger = stored.getPassenger();
        requireActivePassenger(passenger);
        IssuedToken replacement = prepare(passenger, normalizedDeviceId, now);
        stored.setLastUsedAt(now);
        stored.setRevokedAt(now);
        stored.setReplacedByHash(replacement.entity().getTokenHash());
        tokenRepository.saveAndFlush(stored);
        tokenRepository.save(replacement.entity());

        return ApiResponse.success("Biometric login successful.", response(passenger, replacement));
    }

    @Transactional
    public ApiResponse<Void> revoke(String rawToken, String deviceId) {
        tokenRepository.findLockedByTokenHash(hash(rawToken)).ifPresent(stored -> {
            String normalizedDeviceId = normalizeDeviceId(deviceId);
            if (MessageDigest.isEqual(
                    stored.getDeviceId().getBytes(StandardCharsets.UTF_8),
                    normalizedDeviceId.getBytes(StandardCharsets.UTF_8))
                    && stored.getRevokedAt() == null) {
                stored.setRevokedAt(Instant.now());
                tokenRepository.save(stored);
            }
        });
        return ApiResponse.success("Biometric session revoked.");
    }

    @Transactional
    public ApiResponse<Void> revokeDevice(Passenger passenger, String deviceId) {
        if (passenger != null) {
            tokenRepository.revokeActiveForDevice(
                    passenger.getId(), normalizeDeviceId(deviceId), Instant.now());
        }
        return ApiResponse.success("Biometric login disabled on this device.");
    }

    private IssuedToken prepare(Passenger passenger, String deviceId, Instant now) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        Instant expiresAt = now.plus(Duration.ofDays(Math.max(1, refreshExpirationDays)));
        BiometricRefreshToken entity = BiometricRefreshToken.builder()
                .passenger(passenger)
                .tokenHash(hash(rawToken))
                .deviceId(deviceId)
                .createdAt(now)
                .expiresAt(expiresAt)
                .build();
        return new IssuedToken(rawToken, expiresAt, entity);
    }

    private AuthResponse response(Passenger passenger, IssuedToken issued) {
        return AuthResponse.builder()
                .token(jwtUtil.generateFullToken(passenger.getId()))
                .refreshToken(issued.rawToken())
                .refreshTokenExpiresAt(issued.expiresAt())
                .passengerName("Passenger #" + passenger.getId())
                .passengerId(passenger.getId())
                .require2FA(false)
                .build();
    }

    private void requireActivePassenger(Passenger passenger) {
        if (passenger == null || (passenger.getStatus() != PassengerStatus.ACTIVE
                && passenger.getStatus() != PassengerStatus.AVAILABLE)) {
            throw invalidToken();
        }
    }

    private String normalizeDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) throw invalidToken();
        return deviceId.trim();
    }

    private String hash(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw invalidToken();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.trim().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private InvalidBiometricTokenException invalidToken() {
        return new InvalidBiometricTokenException(
                "Biometric session expired or was revoked. Please sign in with your card and OTP again.");
    }

    private record IssuedToken(String rawToken, Instant expiresAt, BiometricRefreshToken entity) {}
}
