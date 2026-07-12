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

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final PassengerRepository passengerRepository;
    private final JwtUtil jwtUtil;
    private final TotpService totpService;
    private final Map<String, Integer> loginAttempts = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> loginCooldowns = new ConcurrentHashMap<>();
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int LOGIN_COOLDOWN_MINUTES = 15;
    private final Map<Long, Integer> totpFailures = new ConcurrentHashMap<>();
    private final Map<Long, Instant> totpCooldowns = new ConcurrentHashMap<>();
    private static final int MAX_TOTP_ATTEMPTS = 3;
    private static final int MAX_TOTP_COOLDOWN_MINUTES = 15;

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
            mask(request.getCardNumber()));

        return ApiResponse.success(
            "Registration successful! " +
            "Please set up Google Authenticator.",
            toPassengerResponse(passenger));
    }

    // LOGIN 

    public ApiResponse<AuthResponse> login(
            LoginRequest request) {
        String cardNumber = request.getCardNumber().trim();
        enforceLoginCooldown(cardNumber);

        Passenger passenger = passengerRepository
            .findByCardNumber(cardNumber)
            .orElseThrow(() -> {
                recordFailedLogin(cardNumber);
                return new InvalidRfidException(
                    "Invalid card number or account status.");
            });

        if (passenger.getStatus() != PassengerStatus.ACTIVE
                && passenger.getStatus() != PassengerStatus.AVAILABLE) {
            recordFailedLogin(cardNumber);
            throw new RuntimeException(
                "Account is " +
                passenger.getStatus().name().toLowerCase());
        }

        clearLoginFailures(cardNumber);

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

        enforceTotpCooldown(passengerId);

        Passenger passenger = passengerRepository
            .findById(passengerId)
            .orElseThrow(() ->
                new PassengerNotFoundException(
                    "Passenger not found."));

        if (passenger.getStatus() != PassengerStatus.ACTIVE
                && passenger.getStatus() != PassengerStatus.AVAILABLE) {
            throw new RuntimeException(
                "Account is " + passenger.getStatus().name().toLowerCase());
        }


        boolean isValid = totpService.verifyCode(
            passenger.getTwofaSecret(),
            request.getTotpCode());

        if (!isValid) {
            recordFailedTotp(passengerId);
            throw new InvalidTotpException(
                "Invalid code. Please try again.");
        }

        clearTotpFailures(passengerId);

        if (!passenger.getIs2FaEnabled()) {
            passenger.setIs2FaEnabled(true);
            log.info("2FA enabled for passenger: {}", passenger.getId());
        }

        if (passenger.getStatus() == PassengerStatus.AVAILABLE) {
            passenger.setStatus(PassengerStatus.ACTIVE);
            log.info("Activated newly issued passenger card: {}", passenger.getId());
        }
        passengerRepository.save(passenger);

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
                .cardNumber(mask(p.getCardNumber()))
                .rfidUid(null)
                .is2FaEnabled(p.getIs2FaEnabled())  
                .status(p.getStatus())
                .createdAt(p.getCreatedAt())
                .build();
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        int visible = Math.min(4, trimmed.length());
        return "****" + trimmed.substring(trimmed.length() - visible);
    }

    private void enforceLoginCooldown(String cardNumber) {
        LocalDateTime lockedUntil = loginCooldowns.get(cardNumber);
        if (lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now())) {
            throw new RuntimeException("Too many login attempts. Please try again later.");
        }
        if (lockedUntil != null) {
            loginCooldowns.remove(cardNumber);
            loginAttempts.remove(cardNumber);
        }
    }

    private void recordFailedLogin(String cardNumber) {
        int attempts = loginAttempts.merge(cardNumber, 1, Integer::sum);
        if (attempts >= MAX_LOGIN_ATTEMPTS) {
            loginCooldowns.put(cardNumber,
                LocalDateTime.now().plusMinutes(LOGIN_COOLDOWN_MINUTES));
            log.warn("Passenger login temporarily locked for card={}", mask(cardNumber));
        }
    }

    private void clearLoginFailures(String cardNumber) {
        loginAttempts.remove(cardNumber);
        loginCooldowns.remove(cardNumber);
    }

    private void enforceTotpCooldown(Long passengerId) {
        Instant lockedUntil = totpCooldowns.get(passengerId);
        if (lockedUntil == null) return;

        Instant now = Instant.now();
        if (lockedUntil.isAfter(now)) {
            long retryAfterSeconds = Math.max(
                1,
                (Duration.between(now, lockedUntil).toMillis() + 999) / 1000);
            throw new InvalidTotpException(
                "Too many incorrect codes. Try again in " + retryAfterSeconds + " seconds.",
                retryAfterSeconds);
        }

        totpCooldowns.remove(passengerId);
    }

    private void recordFailedTotp(Long passengerId) {
        int failures = totpFailures.merge(passengerId, 1, Integer::sum);
        if (failures < MAX_TOTP_ATTEMPTS) return;

        int cooldownMinutes = Math.min(
            failures - MAX_TOTP_ATTEMPTS + 1,
            MAX_TOTP_COOLDOWN_MINUTES);
        Instant lockedUntil = Instant.now().plusSeconds(cooldownMinutes * 60L);
        totpCooldowns.put(passengerId, lockedUntil);

        log.warn(
            "Passenger TOTP temporarily locked: passengerId={}, failures={}, cooldownMinutes={}",
            passengerId,
            failures,
            cooldownMinutes);

        throw new InvalidTotpException(
            "Too many incorrect codes. Try again in " + cooldownMinutes +
                (cooldownMinutes == 1 ? " minute." : " minutes."),
            cooldownMinutes * 60L);
    }

    private void clearTotpFailures(Long passengerId) {
        totpFailures.remove(passengerId);
        totpCooldowns.remove(passengerId);
    }
}
