package com.premier.service;

import com.premier.exception.*;
import com.premier.model.Passenger;
import com.premier.model.PassengerStatus;
import com.premier.repository.PassengerRepository;
import com.premier.request.*;
import com.premier.response.*;
import com.premier.security.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class AuthService {

    private final PassengerRepository passengerRepository;
    private final JwtUtil jwtUtil;
    private final TotpService totpService;

    public AuthService(PassengerRepository passengerRepository,
                       JwtUtil jwtUtil,
                       TotpService totpService) {
        this.passengerRepository = passengerRepository;
        this.jwtUtil = jwtUtil;
        this.totpService = totpService;
    }

    private String generateUserId() {
        return String.valueOf(
            (long)(Math.random() * 90000000) + 10000000);
    }

    @Transactional
    public ApiResponse<PassengerResponse> register(RegisterRequest request) {

        if (passengerRepository.existsByCardNumber(
                request.getCardNumber())) {
            throw new RuntimeException("Card number already registered.");
        }

        if (passengerRepository.existsByRfidUid(
                request.getRfidUid())) {
            throw new RuntimeException("RFID already registered.");
        }

        String userId = generateUserId();
        while (passengerRepository.existsByUserId(userId)) {
            userId = generateUserId();
        }

        String totpSecret = totpService.generateSecret();

        Passenger passenger = Passenger.builder()
                .userId(userId)
                .cardNumber(request.getCardNumber())
                .rfidUid(request.getRfidUid())
                .totpSecret(totpSecret)
                .twoFactorEnabled(true)
                .status(PassengerStatus.ACTIVE)
                .build();

        passengerRepository.save(passenger);
        log.info("New passenger registered: {}", passenger.getUserId());

        return ApiResponse.success(
                "Registration successful. Please set up Google Authenticator.",
                toPassengerResponse(passenger));
    }

    public ApiResponse<AuthResponse> login(LoginRequest request) {

        // ✅ Login using cardNumber
        Passenger passenger = passengerRepository
                .findByCardNumber(request.getCardNumber())
                .orElseThrow(() ->
                    new InvalidRfidException("Card number not found."));

        if (passenger.getStatus() != PassengerStatus.ACTIVE) {
            throw new RuntimeException("Account is " +
                    passenger.getStatus().name().toLowerCase() + ".");
        }

        if (passenger.isTwoFactorEnabled()) {
            String tempToken = jwtUtil.generateTempToken(
                    passenger.getId());
            return ApiResponse.success(
                    "2FA required. Please enter your TOTP code.",
                    AuthResponse.builder()
                            .require2FA(true)
                            .tempToken(tempToken)
                            // ✅ Use userId instead of fullName
                            .passengerName(passenger.getUserId())
                            .build());
        }

        String token = jwtUtil.generateToken(passenger.getId());
        return ApiResponse.success("Login successful.",
                AuthResponse.builder()
                        .token(token)
                        .require2FA(false)
                        .passengerName(passenger.getUserId())
                        .passengerId(passenger.getId())
                        .build());
    }

    public ApiResponse<AuthResponse> verifyTotp(TotpVerifyRequest request) {
        if (!jwtUtil.isTokenValid(request.getTempToken())) {
            throw new InvalidTotpException(
                    "Temp token is invalid or expired.");
        }

        Long passengerId = jwtUtil.extractPassengerId(
                request.getTempToken());
        Passenger passenger = passengerRepository.findById(passengerId)
                .orElseThrow(() ->
                    new PassengerNotFoundException("Passenger not found."));

        boolean isValid = totpService.verifyCode(
                passenger.getTotpSecret(), request.getTotpCode());
        if (!isValid) {
            throw new InvalidTotpException(
                    "Invalid TOTP code. Please try again.");
        }

        String token = jwtUtil.generateToken(passenger.getId());
        return ApiResponse.success("Login successful.",
                AuthResponse.builder()
                        .token(token)
                        .require2FA(false)
                        // ✅ Use userId instead of fullName
                        .passengerName(passenger.getUserId())
                        .passengerId(passenger.getId())
                        .build());
    }

    public ApiResponse<TotpSetupResponse> generateTotpSetup(
            Long passengerId) {
        Passenger passenger = passengerRepository.findById(passengerId)
                .orElseThrow(() ->
                    new PassengerNotFoundException("Passenger not found."));

        // ✅ Use phoneNumber instead of email for TOTP setup
        TotpSetupResponse setup = totpService.generateSetup(
                passenger.getTotpSecret());

        return ApiResponse.success(
                "Scan the QR code with Google Authenticator.", setup);
    }

    public ApiResponse<PassengerResponse> getProfile(
            Passenger passenger) {
        return ApiResponse.success("Profile fetched.",
                toPassengerResponse(passenger));
    }

    // ✅ Updated toPassengerResponse - no fullName, email
    private PassengerResponse toPassengerResponse(Passenger p) {
        return PassengerResponse.builder()
                .id(p.getId())
                .userId(p.getUserId())
                .cardNumber(p.getCardNumber())
                .rfidUid(p.getRfidUid())
                .balance(p.getBalance())
                .twoFactorEnabled(p.isTwoFactorEnabled())
                .status(p.getStatus())
                .createdAt(p.getCreatedAt())
                .build();
    }
}