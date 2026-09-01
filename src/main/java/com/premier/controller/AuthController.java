package com.premier.controller;

import com.premier.request.*;
import com.premier.response.ApiResponse;
import com.premier.model.Passenger;
import com.premier.service.AuthService;
import com.premier.service.BiometricAuthService;
import com.premier.security.JwtUtil;
import com.premier.repository.PassengerRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/passenger/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final PassengerRepository passengerRepository;
    private final BiometricAuthService biometricAuthService;

    @PostMapping("/register")
    public ResponseEntity<?> register(
            @Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(
            authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(
            authService.login(request));
    }

    @GetMapping("/totp/setup")
    public ResponseEntity<?> totpSetup(
            @RequestHeader(value = "Authorization",
                required = false) String authHeader) {
        try {
            if (authHeader == null ||
                !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401)
                    .body(ApiResponse.error(
                        "Authorization header missing."));
            }

            String token = authHeader.substring(7);

            if (!jwtUtil.isTempToken(token)) {
                return ResponseEntity.status(401)
                    .body(ApiResponse.error(
                        "Invalid or expired token. " +
                        "Please login again."));
            }

            Long passengerId =
                jwtUtil.extractPassengerId(token);

            return ResponseEntity.ok(
                authService.getTotpSetup(passengerId));

        } catch (Exception e) {
            return ResponseEntity.status(400)
                .body(ApiResponse.error(
                    "Unable to prepare 2FA setup. Please login again."));
        }
    }

    @PostMapping("/verify-totp")
    public ResponseEntity<?> verifyTotp(
            @Valid @RequestBody TotpVerifyRequest request) {
        return ResponseEntity.ok(
            authService.verifyTotp(request));
    }

    @PostMapping("/biometric/enroll")
    public ResponseEntity<?> enrollBiometrics(
            @AuthenticationPrincipal Passenger passenger,
            @Valid @RequestBody BiometricDeviceRequest request) {
        return ResponseEntity.ok(
            biometricAuthService.enroll(passenger, request.getDeviceId()));
    }

    @PostMapping("/biometric/refresh")
    public ResponseEntity<?> refreshBiometricSession(
            @Valid @RequestBody BiometricTokenRequest request) {
        return ResponseEntity.ok(
            biometricAuthService.refresh(request.getRefreshToken(), request.getDeviceId()));
    }

    @PostMapping("/biometric/revoke")
    public ResponseEntity<?> revokeBiometricSession(
            @Valid @RequestBody BiometricTokenRequest request) {
        return ResponseEntity.ok(
            biometricAuthService.revoke(request.getRefreshToken(), request.getDeviceId()));
    }

    @PostMapping("/biometric/revoke-device")
    public ResponseEntity<?> revokeBiometricDevice(
            @AuthenticationPrincipal Passenger passenger,
            @Valid @RequestBody BiometricDeviceRequest request) {
        return ResponseEntity.ok(
            biometricAuthService.revokeDevice(passenger, request.getDeviceId()));
    }

    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(
            @AuthenticationPrincipal Passenger passenger) {
        return ResponseEntity.ok(
            authService.getProfile(passenger));
    }
}
