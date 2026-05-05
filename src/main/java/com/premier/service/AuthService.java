package com.premier.service;

import com.premier.request.*;
import com.premier.response.*;
import com.premier.exception.*;
import com.premier.model.Passenger;
import com.premier.model.PassengerStatus;
import com.premier.repository.PassengerRepository;
import com.premier.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final PassengerRepository passengerRepository;
    private final JwtUtil jwtUtil;
    private final TotpService totpService;

    //REGISTER 

    @Transactional
    public ApiResponse<PassengerResponse> register(
            RegisterRequest request) {

        if (passengerRepository.existsByCardNumber(
                request.getCardNumber()))
            throw new RuntimeException(
                "Card number already registered.");

        if (passengerRepository.existsByRfidUid(
                request.getRfidUid()))
            throw new RuntimeException(
                "RFID already registered.");

        String twofaSecret = totpService.generateSecret();

        Passenger passenger = Passenger.builder()
                .cardNumber(request.getCardNumber())
                .rfidUid(request.getRfidUid())
                .twofaSecret(twofaSecret)
                .is2FaEnabled(true)  // 
                .status(PassengerStatus.ACTIVE)
                .build();

        passengerRepository.save(passenger);
        log.info("Passenger registered: cardNumber={}", 
            request.getCardNumber());

        return ApiResponse.success(
            "Registration successful! " +
            "Please set up Google Authenticator.",
            toPassengerResponse(passenger));
    }

    // LOGIN 

    public ApiResponse<AuthResponse> login(
            LoginRequest request) {

        Passenger passenger = passengerRepository
            .findByCardNumber(request.getCardNumber())
            .orElseThrow(() ->
                new InvalidRfidException(
                    "Card number not found."));

        if (passenger.getStatus() != PassengerStatus.ACTIVE)
            throw new RuntimeException(
                "Account is " +
                passenger.getStatus().name().toLowerCase());

        String tempToken = jwtUtil.generateTempToken(
            passenger.getId());

        if (!passenger.getIs2FaEnabled()) {
            return ApiResponse.success(
                "Please set up Google Authenticator first.",
                AuthResponse.builder()
                    .require2FA(true)
                    .requireSetup(true)
                    .tempToken(tempToken)
                    .passengerName("Passenger #" + passenger.getId())
                    .build());
        }

        return ApiResponse.success(
            "2FA required. Enter your code.",
            AuthResponse.builder()
                .require2FA(true)
                .requireSetup(false)
                .tempToken(tempToken)
                .passengerName("Passenger #" + passenger.getId())
                .build());
    }

    // GET TOTP SETUP 

    public ApiResponse<TotpSetupResponse> getTotpSetup(Long passengerId) {
        Passenger passenger = passengerRepository
            .findById(passengerId)
            .orElseThrow(() -> new PassengerNotFoundException("Passenger not found."));


        if (passenger.getTwofaSecret() == null) {
            String newSecret = totpService.generateSecret();
            passenger.setTwofaSecret(newSecret);
            passengerRepository.save(passenger); 
            log.info("Generated new TOTP secret for passenger: {}", passengerId);
        }

        String qrCodeUrl = totpService.generateQrCodeUrl(
            passenger.getTwofaSecret(),
            "Passenger #" + passenger.getId());

        return ApiResponse.success(
            "Scan QR code with Google Authenticator.",
            TotpSetupResponse.builder()
                .secret(passenger.getTwofaSecret())
                .qrCodeUrl(qrCodeUrl)
                .manualEntryKey(passenger.getTwofaSecret())
                .is2FaEnabled(passenger.getIs2FaEnabled())
                .build());
    }

    // VERIFY TOTP

    @Transactional
    public ApiResponse<AuthResponse> verifyTotp(
            TotpVerifyRequest request) {

        if (request.getTempToken() == null ||
            request.getTempToken().isBlank()) {
            throw new InvalidTotpException(
                "Temp token is missing. Please login again.");
        }

        if (!jwtUtil.isTokenValid(request.getTempToken()) ||
            !jwtUtil.isTempToken(request.getTempToken())) {
            throw new InvalidTotpException(
                "Session expired. Please login again.");
        }

        Long passengerId = jwtUtil.extractPassengerId(
            request.getTempToken());

        Passenger passenger = passengerRepository
            .findById(passengerId)
            .orElseThrow(() ->
                new PassengerNotFoundException(
                    "Passenger not found."));


        boolean isValid = totpService.verifyCode(
            passenger.getTwofaSecret(),
            request.getTotpCode());

        if (!isValid)
            throw new InvalidTotpException(
                "Invalid code. Please try again.");

        if (!passenger.getIs2FaEnabled()) {
            passenger.setIs2FaEnabled(true);
            passengerRepository.save(passenger);
            log.info("2FA enabled for passenger: {}", passenger.getId());
        }

        String fullToken = jwtUtil.generateFullToken(
            passenger.getId());

        return ApiResponse.success(
            "Login successful!",
            AuthResponse.builder()
                .token(fullToken)
                .require2FA(false)
                .passengerName("Passenger #" + passenger.getId())
                .passengerId(passenger.getId())
                .build());
    }

    // PROFILE

    public ApiResponse<PassengerResponse> getProfile(
            Passenger passenger) {
        return ApiResponse.success(
            "Profile fetched.",
            toPassengerResponse(passenger));
    }

    //HELPERS 

    private PassengerResponse toPassengerResponse(Passenger p) {
        return PassengerResponse.builder()
                .id(p.getId())
                .balance(p.getBalance())
                .cardNumber(p.getCardNumber())
                .rfidUid(p.getRfidUid())
                .is2FaEnabled(p.getIs2FaEnabled())  
                .status(p.getStatus())
                .createdAt(p.getCreatedAt())
                .build();
    }
}