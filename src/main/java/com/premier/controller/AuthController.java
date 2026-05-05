package com.premier.controller;

import com.premier.request.*;
import com.premier.response.ApiResponse;
import com.premier.model.Passenger;
import com.premier.service.AuthService;
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
                    "Failed: " + e.getMessage()));
        }
    }

    @PostMapping("/verify-totp")
    public ResponseEntity<?> verifyTotp(
            @Valid @RequestBody TotpVerifyRequest request) {
        return ResponseEntity.ok(
            authService.verifyTotp(request));
    }

    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(
            @AuthenticationPrincipal Passenger passenger) {
        return ResponseEntity.ok(
            authService.getProfile(passenger));
    }
}