package com.premier.service;

import com.premier.driver.model.DriverShift;
import com.premier.driver.model.OnboardStatus;
import com.premier.driver.model.PassengerOnboard;
import com.premier.driver.model.ShiftStatus;
import com.premier.driver.repository.DriverShiftRepository;
import com.premier.driver.repository.PassengerOnboardRepository;
import com.premier.device.security.DevicePrincipal;
import com.premier.device.service.DeviceService;
import com.premier.model.*;
import com.premier.repository.FareQrTokenRepository;
import com.premier.repository.PassengerRepository;
import com.premier.repository.TransactionRepository;
import com.premier.rfid.DeviceFareRequest;
import com.premier.response.ApiResponse;
import com.premier.response.FarePaymentResponse;
import com.premier.response.FareQrTokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class FarePaymentService {

    private static final BigDecimal FIXED_FARE = new BigDecimal("60.00");
    private static final long COOLDOWN_SECONDS = 8L;
    private static final String QR_PREFIX = "PREMIER-FARE:";
    private static final String MOBILE_NFC_PREFIX = "PREMIER-NFC:";

    private static final double SM_LIPA_LAT = 13.954781;
    private static final double SM_LIPA_LNG = 121.163096;
    private static final double SM_BATANGAS_LAT = 13.7567;
    private static final double SM_BATANGAS_LNG = 121.0584;
    private static final double GPS_RADIUS_KM = 5.0;
    private static final int GPS_TIMEOUT_MINUTES = 5;

    private final PassengerRepository passengerRepository;
    private final TransactionRepository transactionRepository;
    private final FareQrTokenRepository fareQrTokenRepository;
    private final DriverShiftRepository driverShiftRepository;
    private final PassengerOnboardRepository onboardRepository;
    private final FirebaseService firebaseService;
    private final DeviceService deviceService;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, LocalDateTime> cooldownMap = new ConcurrentHashMap<>();

    @Value("${fare.qr-expiration-seconds:60}")
    private long qrExpirationSeconds;

    @Transactional
    public ApiResponse<FareQrTokenResponse> generateQrToken(Passenger principal) {
        Passenger passenger = passengerRepository.findLockedById(principal.getId())
                .orElseThrow(() -> new RuntimeException("Passenger not found."));

        if (passenger.getStatus() != PassengerStatus.ACTIVE) {
            throw new RuntimeException("This RFID card is currently inactive or frozen. Please contact Premier Transport support.");
        }

        expireExistingQrTokens(passenger.getId());

        String rawToken = newToken();
        String tokenHash = sha256(rawToken);
        LocalDateTime expiresAt = nowUtc().plusSeconds(qrExpirationSeconds);

        FareQrToken token = FareQrToken.builder()
                .tokenHash(tokenHash)
                .passenger(passenger)
                .status(FareQrTokenStatus.ACTIVE)
                .expiresAt(expiresAt)
                .build();
        fareQrTokenRepository.save(token);

        return ApiResponse.success("QR fare token generated.",
                FareQrTokenResponse.builder()
                        .token(rawToken)
                        .payload(QR_PREFIX + rawToken)
                        .passengerId(passenger.getId())
                        .cardNumber(mask(passenger.getCardNumber()))
                        .expiresAt(expiresAt)
                        .expiresInSeconds(qrExpirationSeconds)
                        .build());
    }

    @Transactional
    public ApiResponse<FareQrTokenResponse> generateMobileNfcToken(Passenger principal) {
        Passenger passenger = passengerRepository.findLockedById(principal.getId())
                .orElseThrow(() -> new RuntimeException("Passenger not found."));

        if (passenger.getStatus() != PassengerStatus.ACTIVE) {
            throw new RuntimeException("Account is " + passenger.getStatus().name().toLowerCase() + ".");
        }

        expireExistingQrTokens(passenger.getId());

        String rawToken = newToken();
        String tokenHash = sha256(rawToken);
        LocalDateTime expiresAt = nowUtc().plusSeconds(qrExpirationSeconds);

        FareQrToken token = FareQrToken.builder()
                .tokenHash(tokenHash)
                .passenger(passenger)
                .status(FareQrTokenStatus.ACTIVE)
                .expiresAt(expiresAt)
                .build();
        fareQrTokenRepository.save(token);

        return ApiResponse.success("Mobile NFC fare token generated.",
                FareQrTokenResponse.builder()
                        .token(rawToken)
                        .payload(MOBILE_NFC_PREFIX + rawToken)
                        .passengerId(passenger.getId())
                        .cardNumber(mask(passenger.getCardNumber()))
                        .expiresAt(expiresAt)
                        .expiresInSeconds(qrExpirationSeconds)
                        .build());
    }

    @Transactional
    public ApiResponse<FarePaymentResponse> processQrPayment(String payload, String plateNumber) {
        String rawToken = normalizeQrPayload(payload);
        FareQrToken token = fareQrTokenRepository.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new RuntimeException("Invalid QR fare token."));

        if (token.getStatus() == FareQrTokenStatus.USED) {
            throw new RuntimeException("QR fare token has already been used.");
        }

        if (token.getStatus() == FareQrTokenStatus.EXPIRED) {
            throw new RuntimeException("QR fare token expired. Please generate a new one.");
        }

        if (token.getExpiresAt().isBefore(nowUtc())) {
            token.setStatus(FareQrTokenStatus.EXPIRED);
            fareQrTokenRepository.save(token);
            throw new RuntimeException("QR fare token expired. Please generate a new one.");
        }

        ApiResponse<FarePaymentResponse> response = processPassengerFare(
                token.getPassenger().getId(),
                null,
                "QR",
                plateNumber,
                false);

        token.setStatus(FareQrTokenStatus.USED);
        token.setUsedAt(LocalDateTime.now());
        token.setUsedReferenceNumber(response.getData().getReferenceNumber());
        fareQrTokenRepository.save(token);

        return response;
    }

    @Transactional
    public ApiResponse<FarePaymentResponse> processQrPayment(DeviceFareRequest request,
                                                             DevicePrincipal device) {
        requireDeviceRequest(request);
        String key = idempotencyKey(request);
        return transactionRepository.findByIdempotencyKey(key)
                .map(tx -> ApiResponse.success("Already processed.", toFarePaymentResponse(tx, "QR")))
                .orElseGet(() -> {
                    validateDevicePaymentRequest(request, device);
                    String rawToken = normalizeQrPayload(request.getPayload());
                    FareQrToken token = fareQrTokenRepository.findByTokenHash(sha256(rawToken))
                            .orElseThrow(() -> new RuntimeException("Invalid QR fare token."));

                    if (token.getStatus() == FareQrTokenStatus.USED) {
                        throw new RuntimeException("QR fare token has already been used.");
                    }
                    if (token.getStatus() == FareQrTokenStatus.EXPIRED || token.getExpiresAt().isBefore(nowUtc())) {
                        token.setStatus(FareQrTokenStatus.EXPIRED);
                        fareQrTokenRepository.save(token);
                        throw new RuntimeException("QR fare token expired. Please generate a new one.");
                    }

                    ApiResponse<FarePaymentResponse> response = processPassengerFare(
                            token.getPassenger().getId(),
                            null,
                            "QR",
                            request.getPlateNumber(),
                            false,
                            key,
                            device,
                            request);

                    token.setStatus(FareQrTokenStatus.USED);
                    token.setUsedAt(LocalDateTime.now());
                    token.setUsedReferenceNumber(response.getData().getReferenceNumber());
                    fareQrTokenRepository.save(token);
                    return response;
                });
    }

    @Transactional
    public ApiResponse<FarePaymentResponse> processMobileNfcTokenPayment(String payload, String plateNumber) {
        String rawToken = normalizeMobileNfcPayload(payload);
        FareQrToken token = fareQrTokenRepository.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new RuntimeException("Invalid mobile NFC fare token."));

        if (token.getStatus() == FareQrTokenStatus.USED) {
            throw new RuntimeException("Mobile NFC token has already been used.");
        }

        if (token.getStatus() == FareQrTokenStatus.EXPIRED) {
            throw new RuntimeException("Mobile NFC token expired. Please generate a new one.");
        }

        if (token.getExpiresAt().isBefore(nowUtc())) {
            token.setStatus(FareQrTokenStatus.EXPIRED);
            fareQrTokenRepository.save(token);
            throw new RuntimeException("Mobile NFC token expired. Please generate a new one.");
        }

        ApiResponse<FarePaymentResponse> response = processPassengerFare(
                token.getPassenger().getId(),
                null,
                "NFC",
                plateNumber,
                true);

        token.setStatus(FareQrTokenStatus.USED);
        token.setUsedAt(LocalDateTime.now());
        token.setUsedReferenceNumber(response.getData().getReferenceNumber());
        fareQrTokenRepository.save(token);

        return response;
    }

    @Transactional
    public ApiResponse<FarePaymentResponse> processMobileNfcTokenPayment(DeviceFareRequest request,
                                                                         DevicePrincipal device) {
        requireDeviceRequest(request);
        String key = idempotencyKey(request);
        return transactionRepository.findByIdempotencyKey(key)
                .map(tx -> ApiResponse.success("Already processed.", toFarePaymentResponse(tx, "NFC")))
                .orElseGet(() -> {
                    validateDevicePaymentRequest(request, device);
                    String rawToken = normalizeMobileNfcPayload(
                            request.getMobileNfcToken() != null && !request.getMobileNfcToken().isBlank()
                                    ? request.getMobileNfcToken()
                                    : request.getPayload());
                    FareQrToken token = fareQrTokenRepository.findByTokenHash(sha256(rawToken))
                            .orElseThrow(() -> new RuntimeException("Invalid mobile NFC fare token."));

                    if (token.getStatus() == FareQrTokenStatus.USED) {
                        throw new RuntimeException("Mobile NFC token has already been used.");
                    }
                    if (token.getStatus() == FareQrTokenStatus.EXPIRED || token.getExpiresAt().isBefore(nowUtc())) {
                        token.setStatus(FareQrTokenStatus.EXPIRED);
                        fareQrTokenRepository.save(token);
                        throw new RuntimeException("Mobile NFC token expired. Please generate a new one.");
                    }

                    ApiResponse<FarePaymentResponse> response = processPassengerFare(
                            token.getPassenger().getId(),
                            null,
                            "NFC",
                            request.getPlateNumber(),
                            true,
                            key,
                            device,
                            request);

                    token.setStatus(FareQrTokenStatus.USED);
                    token.setUsedAt(LocalDateTime.now());
                    token.setUsedReferenceNumber(response.getData().getReferenceNumber());
                    fareQrTokenRepository.save(token);
                    return response;
                });
    }

    @Transactional
    public ApiResponse<FarePaymentResponse> processPassengerNfcPayment(Passenger principal, String plateNumber) {
        return processPassengerFare(principal.getId(), null, "NFC", plateNumber, true);
    }

    @Transactional
    public ApiResponse<FarePaymentResponse> processRfidPayment(String rfidUid, String plateNumber) {
        if (rfidUid == null || rfidUid.trim().isEmpty()) {
            throw new RuntimeException("RFID UID is required.");
        }

        String normalizedUid = rfidUid.trim().toUpperCase();
        Passenger passenger = passengerRepository.findLockedByRfidUid(normalizedUid)
                .orElseThrow(() -> new RuntimeException("Card not recognized. Please register your card."));

        return processPassengerFare(passenger.getId(), normalizedUid, "RFID", plateNumber, true);
    }

    @Transactional
    public ApiResponse<FarePaymentResponse> processRfidPayment(DeviceFareRequest request,
                                                               DevicePrincipal device) {
        requireDeviceRequest(request);
        if (request.getRfidUid() == null || request.getRfidUid().trim().isEmpty()) {
            throw new RuntimeException("RFID UID is required.");
        }
        String key = idempotencyKey(request);
        return transactionRepository.findByIdempotencyKey(key)
                .map(tx -> ApiResponse.success("Already processed.", toFarePaymentResponse(tx, "RFID")))
                .orElseGet(() -> {
                    validateDevicePaymentRequest(request, device);
                    String normalizedUid = request.getRfidUid().trim().toUpperCase();
                    Passenger passenger = passengerRepository.findLockedByRfidUid(normalizedUid)
                            .orElseThrow(() -> new RuntimeException("Card not recognized. Please register your card."));
                    return processPassengerFare(passenger.getId(), normalizedUid, "RFID",
                            request.getPlateNumber(), true, key, device, request);
                });
    }

    private ApiResponse<FarePaymentResponse> processPassengerFare(
            Long passengerId,
            String rfidUid,
            String source,
            String plateNumber,
            boolean useCooldown) {
        return processPassengerFare(passengerId, rfidUid, source, plateNumber, useCooldown,
                null, null, null);
    }

    private ApiResponse<FarePaymentResponse> processPassengerFare(
            Long passengerId,
            String rfidUid,
            String source,
            String plateNumber,
            boolean useCooldown,
            String idempotencyKey,
            DevicePrincipal device,
            DeviceFareRequest request) {

        Passenger passenger = passengerRepository.findLockedById(passengerId)
                .orElseThrow(() -> new RuntimeException("Passenger not found."));

        if (passenger.getStatus() != PassengerStatus.ACTIVE) {
            throw new RuntimeException("Account is " + passenger.getStatus().name().toLowerCase() + ".");
        }

        String cooldownKey = source + ":" + passenger.getId();
        if (useCooldown) {
            LocalDateTime lastPayment = cooldownMap.get(cooldownKey);
            if (lastPayment != null && lastPayment.plusSeconds(COOLDOWN_SECONDS).isAfter(LocalDateTime.now())) {
                throw new RuntimeException("Payment already processed recently. Please wait a moment.");
            }
        }

        BigDecimal fare = fareFor(passenger);
        String discountType = discountTypeFor(passenger);

        if (passenger.getBalance().compareTo(fare) < 0) {
            throw new RuntimeException("Insufficient balance. Current: PHP " + passenger.getBalance() + ". Please top up.");
        }

        BigDecimal balanceBefore = passenger.getBalance();
        BigDecimal balanceAfter = balanceBefore.subtract(fare);
        passenger.setBalance(balanceAfter);
        passengerRepository.save(passenger);

        String normalizedPlate = normalizePlate(plateNumber);
        String refNumber = source + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        Transaction tx = Transaction.builder()
                .passenger(passenger)
                .type(TransactionType.FARE_DEDUCTION)
                .status(TransactionStatus.SUCCESS)
                .amount(fare)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .referenceNumber(refNumber)
                .idempotencyKey(idempotencyKey)
                .deviceId(device != null ? device.deviceId() : null)
                .requestNonce(request != null ? clean(request.getRequestNonce()) : null)
                .requestTimestamp(request != null ? parseRequestTimestamp(request.getRequestTimestamp()) : null)
                .description(source + " Fare Payment" + (normalizedPlate != null ? " | " + normalizedPlate : ""))
                .build();
        transactionRepository.save(tx);

        recordOnboardIfPossible(passenger, normalizedPlate);

        if (passenger.getFcmToken() != null) {
            firebaseService.sendFareDeduction(
                    passenger.getFcmToken(),
                    fare.toString(),
                    balanceAfter.toString(),
                    source + (normalizedPlate != null
                            ? " | " + normalizedPlate
                            : ""));
        }

        if (useCooldown) {
            cooldownMap.put(cooldownKey, now);
        }

        FarePaymentResponse data = FarePaymentResponse.builder()
                .cardNumber(mask(passenger.getCardNumber()))
                .rfidUid(null)
                .baseFare(FIXED_FARE)
                .deductedFare(fare)
                .discountType(discountType)
                .balanceBefore(balanceBefore)
                .remainingBalance(balanceAfter)
                .referenceNumber(refNumber)
                .source(source)
                .plateNumber(normalizedPlate)
                .timestamp(now)
                .build();

        log.info("{} fare payment success [passenger={}, ref={}, {}->{}]",
                source, passenger.getId(), refNumber, balanceBefore, balanceAfter);

        return ApiResponse.success("Fare deducted successfully!", data);
    }

    private void recordOnboardIfPossible(Passenger passenger, String plateNumber) {
        if (plateNumber == null) {
            return;
        }

        driverShiftRepository.findByVehiclePlateNumberAndStatus(plateNumber, ShiftStatus.ACTIVE)
                .ifPresent(shift -> {
                    long onboard = onboardRepository.countByShiftIdAndStatus(shift.getId(), OnboardStatus.ONBOARD);

                    if (onboard >= shift.getVehicle().getTotalCapacity()) {
                        log.warn("Vehicle {} is at full capacity. Fare was deducted but onboard record was skipped.", plateNumber);
                        return;
                    }

                    PassengerOnboard record = PassengerOnboard.builder()
                            .shift(shift)
                            .passenger(passenger)
                            .dropOffLocation(determineDropOffLocation(shift))
                            .fare(fareFor(passenger))
                            .passengerCount(1)
                            .status(OnboardStatus.ONBOARD)
                            .build();
                    onboardRepository.save(record);
                });
    }

    private void validateDevicePaymentRequest(DeviceFareRequest request, DevicePrincipal device) {
        requireDeviceRequest(request);
        deviceService.requirePlateAssignment(device, request.getPlateNumber());
        deviceService.validateFreshNonce(device, request.getRequestNonce(), request.getRequestTimestamp());
        if (idempotencyKey(request).length() < 12) {
            throw new RuntimeException("Request ID is invalid.");
        }
    }

    private void requireDeviceRequest(DeviceFareRequest request) {
        if (request == null) {
            throw new RuntimeException("Payment request is required.");
        }
    }

    private String idempotencyKey(DeviceFareRequest request) {
        String key = clean(request.getIdempotencyKey());
        if (key == null) key = clean(request.getRequestId());
        if (key == null) {
            throw new RuntimeException("Request ID is required.");
        }
        return key;
    }

    private LocalDateTime parseRequestTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.parse(timestamp.trim()), ZoneOffset.UTC);
    }

    private FarePaymentResponse toFarePaymentResponse(Transaction tx, String source) {
        return FarePaymentResponse.builder()
                .cardNumber(mask(tx.getPassenger().getCardNumber()))
                .rfidUid(null)
                .baseFare(FIXED_FARE)
                .deductedFare(tx.getAmount())
                .discountType(discountTypeFor(tx.getPassenger()))
                .balanceBefore(tx.getBalanceBefore())
                .remainingBalance(tx.getBalanceAfter())
                .referenceNumber(tx.getReferenceNumber())
                .source(source)
                .timestamp(tx.getCreatedAt())
                .build();
    }

    private String clean(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private BigDecimal fareFor(Passenger passenger) {
        if (Boolean.TRUE.equals(passenger.getDiscountEligible())
                && passenger.getCardCategory() != null
                && passenger.getCardCategory() != PassengerCardCategory.REGULAR) {
            return FIXED_FARE.multiply(new BigDecimal("0.80"));
        }
        return FIXED_FARE;
    }

    private String discountTypeFor(Passenger passenger) {
        if (Boolean.TRUE.equals(passenger.getDiscountEligible())
                && passenger.getCardCategory() != null
                && passenger.getCardCategory() != PassengerCardCategory.REGULAR) {
            return passenger.getCardCategory().name();
        }
        return "REGULAR";
    }

    private void expireExistingQrTokens(Long passengerId) {
        fareQrTokenRepository.findByPassengerIdAndStatus(passengerId, FareQrTokenStatus.ACTIVE)
                .forEach(token -> {
                    token.setStatus(FareQrTokenStatus.EXPIRED);
                    fareQrTokenRepository.save(token);
                });
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private String normalizeQrPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new RuntimeException("QR fare token is required.");
        }

        String trimmed = payload.trim();
        return trimmed.startsWith(QR_PREFIX) ? trimmed.substring(QR_PREFIX.length()) : trimmed;
    }

    private String normalizeMobileNfcPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new RuntimeException("Mobile NFC fare token is required.");
        }

        String trimmed = payload.trim();
        return trimmed.startsWith(MOBILE_NFC_PREFIX) ? trimmed.substring(MOBILE_NFC_PREFIX.length()) : trimmed;
    }

    private String normalizePlate(String plateNumber) {
        if (plateNumber == null || plateNumber.trim().isEmpty()) {
            return null;
        }
        return plateNumber.trim().toUpperCase();
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        int visible = Math.min(4, trimmed.length());
        return "****" + trimmed.substring(trimmed.length() - visible);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            throw new RuntimeException("Unable to secure fare token.");
        }
    }

    private String determineDropOffLocation(DriverShift shift) {
        Double lat = shift.getCurrentLatitude();
        Double lng = shift.getCurrentLongitude();
        LocalDateTime lastUpdate = shift.getLastLocationUpdate();

        if (lat == null || lng == null || lastUpdate == null ||
                lastUpdate.isBefore(LocalDateTime.now().minusMinutes(GPS_TIMEOUT_MINUTES))) {
            return "SM Lipa / SM Batangas";
        }

        double distLipa = calculateDistance(lat, lng, SM_LIPA_LAT, SM_LIPA_LNG);
        double distBatangas = calculateDistance(lat, lng, SM_BATANGAS_LAT, SM_BATANGAS_LNG);

        if (distLipa < GPS_RADIUS_KM) {
            return "SM Batangas";
        }

        if (distBatangas < GPS_RADIUS_KM) {
            return "SM Lipa";
        }

        return "SM Lipa / SM Batangas";
    }

    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        final int radiusKm = 6371;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lngDistance = Math.toRadians(lng2 - lng1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return radiusKm * c;
    }
}
