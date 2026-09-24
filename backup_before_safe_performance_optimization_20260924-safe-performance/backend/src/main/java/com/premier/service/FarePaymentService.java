package com.premier.service;

import com.premier.driver.model.DriverShift;
import com.premier.driver.model.ShiftStatus;
import com.premier.driver.repository.DriverShiftRepository;
import com.premier.driver.repository.VehicleRepository;
import com.premier.device.security.DevicePrincipal;
import com.premier.device.service.DeviceService;
import com.premier.model.*;
import com.premier.repository.FareQrTokenRepository;
import com.premier.repository.PassengerRepository;
import com.premier.repository.TransactionRepository;
import com.premier.payment.service.FarePaymentAttemptService;
import com.premier.rfid.DeviceFareRequest;
import com.premier.response.ApiResponse;
import com.premier.response.FarePaymentResponse;
import com.premier.response.FareQrTokenResponse;
import com.premier.response.FareQrStatusResponse;
import com.premier.staffcash.service.StaffCashFareService;
import com.premier.realtime.RealtimeEventPublisher;
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
import java.time.ZoneId;
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
    private final VehicleRepository vehicleRepository;
    private final FirebaseService firebaseService;
    private final DeviceService deviceService;
    private final FarePaymentAttemptService farePaymentAttemptService;
    private final StaffCashFareService staffCashFareService;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final com.premier.payment.service.PaymentIdentity paymentIdentity;
    private final com.premier.payment.service.PaymentFailureRecorder paymentFailureRecorder;
    private final com.premier.payment.service.PaymentNotificationService paymentNotifications;
    private final com.premier.trip.service.VehicleTripService tripService;

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${fare.qr-expiration-seconds:60}")
    private long qrExpirationSeconds;

    @Transactional
    public ApiResponse<FareQrTokenResponse> generateQrToken(Passenger principal) {
        Passenger passenger = passengerRepository.findLockedById(principal.getId())
                .orElseThrow(() -> new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Passenger not found."));

        if (passenger.getStatus() != PassengerStatus.ACTIVE) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "This RFID card is currently inactive or frozen. Please contact Premier Transport support.");
        }

        expireExistingQrTokens(passenger.getId());

        String rawToken = newToken();
        String tokenHash = sha256(rawToken);
        LocalDateTime expiresAt = nowManila().plusSeconds(qrExpirationSeconds);

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
                .orElseThrow(() -> new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Passenger not found."));

        if (passenger.getStatus() != PassengerStatus.ACTIVE) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Account is inactive.");
        }

        expireExistingQrTokens(passenger.getId());

        String rawToken = newToken();
        String tokenHash = sha256(rawToken);
        LocalDateTime expiresAt = nowManila().plusSeconds(qrExpirationSeconds);

        FareQrToken token = FareQrToken.builder()
                .tokenHash(tokenHash)
                .purpose("NFC")
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
        try {
            String rawToken = normalizeQrPayload(payload);
            FareQrToken token = lockedToken(sha256(rawToken))
                    .orElseThrow(() -> new com.premier.exception.ClientException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_QR", "Invalid QR fare token."));
            requirePurpose(token, "QR");

            if (token.getStatus() == FareQrTokenStatus.USED) {
                throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "QR_ALREADY_USED", "QR authorization has already been used.");
            }

            if (token.getStatus() == FareQrTokenStatus.EXPIRED) {
                throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.GONE, "QR_EXPIRED", "QR fare token expired. Please generate a new one.");
            }

            if (token.getExpiresAt().isBefore(nowManila())) {
                token.setStatus(FareQrTokenStatus.EXPIRED);
                fareQrTokenRepository.save(token);
                throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.GONE, "QR_EXPIRED", "QR fare token expired. Please generate a new one.");
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
        } catch (RuntimeException ex) {
            recordPaymentFailure(PaymentMethod.QR, null, null, plateNumber, null, null, ex);
            throw ex;
        }
    }

    @Transactional
    public ApiResponse<FareQrStatusResponse> getQrTokenStatus(Passenger principal, String payload) {
        String rawToken = normalizeQrPayload(payload);
        FareQrToken token = lockedToken(sha256(rawToken))
                .orElseThrow(() -> new com.premier.exception.ClientException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_QR", "Invalid QR fare token."));
            requirePurpose(token, "QR");

        if (!token.getPassenger().getId().equals(principal.getId())) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_QR", "Invalid QR fare token.");
        }

        if (token.getStatus() == FareQrTokenStatus.ACTIVE && token.getExpiresAt().isBefore(nowManila())) {
            token.setStatus(FareQrTokenStatus.EXPIRED);
            fareQrTokenRepository.save(token);
        }

        FarePaymentResponse payment = null;
        if (token.getStatus() == FareQrTokenStatus.USED && token.getUsedReferenceNumber() != null) {
            payment = transactionRepository
                    .findByReferenceNumberAndPassengerId(token.getUsedReferenceNumber(), principal.getId())
                    .map(tx -> toFarePaymentResponse(tx, "QR"))
                    .orElse(null);
        }

        long remaining = Math.max(0, java.time.Duration.between(nowManila(), token.getExpiresAt()).getSeconds());
        return ApiResponse.success("QR fare token status fetched.",
                FareQrStatusResponse.builder()
                        .status(token.getStatus().name())
                        .expiresAt(token.getExpiresAt())
                        .expiresInSeconds(remaining)
                        .payment(payment)
                        .build());
    }

    @Transactional
    public ApiResponse<FarePaymentResponse> processQrPayment(DeviceFareRequest request,
                                                             DevicePrincipal device) {
        try {
            requireDeviceRequest(request);
            String key = idempotencyKey(request);
            return findExistingTransaction(request, key, device, "QR")
                    .map(tx -> ApiResponse.success("Already processed.", paymentIdentity.response(tx.getResponseSnapshot())))
                    .orElseGet(() -> {
                        validateDevicePaymentRequest(request, device);
                        String rawToken = normalizeQrPayload(request.getPayload());
                        FareQrToken token = lockedToken(sha256(rawToken))
                                .orElseThrow(() -> new com.premier.exception.ClientException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_QR", "Invalid QR fare token."));
            requirePurpose(token, "QR");

                        if (token.getStatus() == FareQrTokenStatus.USED) {
                            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "QR_ALREADY_USED", "QR authorization has already been used.");
                        }
                        if ((token.getStatus() == FareQrTokenStatus.EXPIRED || token.getExpiresAt().isBefore(nowManila()))
                                ) {
                            token.setStatus(FareQrTokenStatus.EXPIRED);
                            fareQrTokenRepository.save(token);
                            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.GONE, "QR_EXPIRED", "QR fare token expired. Please generate a new one.");
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
        } catch (RuntimeException ex) {
            recordPaymentFailure(PaymentMethod.QR, null, null, request != null ? request.getPlateNumber() : null,
                    device != null ? device.deviceId() : null, request, ex);
            throw ex;
        }
    }

    @Transactional
    public ApiResponse<FarePaymentResponse> processMobileNfcTokenPayment(String payload, String plateNumber) {
        try {
            String rawToken = normalizeMobileNfcPayload(payload);
            FareQrToken token = lockedToken(sha256(rawToken))
                    .orElseThrow(() -> new com.premier.exception.ClientException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_NFC", "Invalid mobile NFC fare token."));
            requirePurpose(token, "NFC");

            if (token.getStatus() == FareQrTokenStatus.USED) {
                throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "QR_ALREADY_USED", "NFC authorization has already been used.");
            }

            if (token.getStatus() == FareQrTokenStatus.EXPIRED) {
                throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.GONE, "NFC_EXPIRED", "Mobile NFC token expired. Please generate a new one.");
            }

            if (token.getExpiresAt().isBefore(nowManila())) {
                token.setStatus(FareQrTokenStatus.EXPIRED);
                fareQrTokenRepository.save(token);
                throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.GONE, "NFC_EXPIRED", "Mobile NFC token expired. Please generate a new one.");
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
        } catch (RuntimeException ex) {
            recordPaymentFailure(PaymentMethod.NFC, null, null, plateNumber, null, null, ex);
            throw ex;
        }
    }

    @Transactional
    public ApiResponse<FarePaymentResponse> processMobileNfcTokenPayment(DeviceFareRequest request,
                                                                         DevicePrincipal device) {
        try {
            requireDeviceRequest(request);
            String key = idempotencyKey(request);
            return findExistingTransaction(request, key, device, "NFC")
                    .map(tx -> ApiResponse.success("Already processed.", paymentIdentity.response(tx.getResponseSnapshot())))
                    .orElseGet(() -> {
                        validateDevicePaymentRequest(request, device);
                        String rawToken = normalizeMobileNfcPayload(
                                request.getMobileNfcToken() != null && !request.getMobileNfcToken().isBlank()
                                        ? request.getMobileNfcToken()
                                        : request.getPayload());
                        FareQrToken token = lockedToken(sha256(rawToken))
                                .orElseThrow(() -> new com.premier.exception.ClientException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_NFC", "Invalid mobile NFC fare token."));
            requirePurpose(token, "NFC");

                        if (token.getStatus() == FareQrTokenStatus.USED) {
                            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "QR_ALREADY_USED", "NFC authorization has already been used.");
                        }
                        if (token.getStatus() == FareQrTokenStatus.EXPIRED || token.getExpiresAt().isBefore(nowManila())) {
                            token.setStatus(FareQrTokenStatus.EXPIRED);
                            fareQrTokenRepository.save(token);
                            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.GONE, "NFC_EXPIRED", "Mobile NFC token expired. Please generate a new one.");
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
        } catch (RuntimeException ex) {
            recordPaymentFailure(PaymentMethod.NFC, null, null, request != null ? request.getPlateNumber() : null,
                    device != null ? device.deviceId() : null, request, ex);
            throw ex;
        }
    }

    @Transactional
    public ApiResponse<FarePaymentResponse> processPassengerNfcPayment(Passenger principal, String plateNumber) {
        try {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.GONE,
                    "USE_TERMINAL", "Present a generated NFC payment token to an authorized terminal.");
        } catch (RuntimeException ex) {
            recordPaymentFailure(PaymentMethod.NFC, principal != null ? principal.getId() : null, null, plateNumber, null, null, ex);
            throw ex;
        }
    }

    @Transactional
    public ApiResponse<FarePaymentResponse> processRfidPayment(String rfidUid, String plateNumber) {
        try {
            if (rfidUid == null || rfidUid.trim().isEmpty()) {
                throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "RFID UID is required.");
            }

            String normalizedUid = rfidUid.trim().toUpperCase();
            Passenger passenger = passengerRepository.findLockedByRfidUid(normalizedUid)
                    .orElseThrow(() -> new com.premier.exception.ClientException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RFID", "Card not recognized. Please register your card."));

            return processPassengerFare(passenger.getId(), normalizedUid, "RFID", plateNumber, true);
        } catch (RuntimeException ex) {
            recordPaymentFailure(PaymentMethod.RFID, null, rfidUid, plateNumber, null, null, ex);
            throw ex;
        }
    }

    @Transactional
    public ApiResponse<FarePaymentResponse> processRfidPayment(DeviceFareRequest request,
                                                               DevicePrincipal device) {
        try {
            requireDeviceRequest(request);
            if (request.getRfidUid() == null || request.getRfidUid().trim().isEmpty()) {
                throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "RFID UID is required.");
            }
            if (staffCashFareService.isStaffCashCard(request.getRfidUid())) {
                return staffCashFareService.process(request, device);
            }
            String key = idempotencyKey(request);
            return findExistingTransaction(request, key, device, "RFID")
                    .map(tx -> ApiResponse.success("Already processed.", paymentIdentity.response(tx.getResponseSnapshot())))
                    .orElseGet(() -> {
                        validateDevicePaymentRequest(request, device);
                        String normalizedUid = request.getRfidUid().trim().toUpperCase();
                        Passenger passenger = passengerRepository.findLockedByRfidUid(normalizedUid)
                                .orElseThrow(() -> new com.premier.exception.ClientException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RFID", "Card not recognized. Please register your card."));
                        return processPassengerFare(passenger.getId(), normalizedUid, "RFID",
                                request.getPlateNumber(), true, key, device, request);
                    });
        } catch (RuntimeException ex) {
            recordPaymentFailure(PaymentMethod.RFID, null, request != null ? request.getRfidUid() : null,
                    request != null ? request.getPlateNumber() : null, device != null ? device.deviceId() : null, request, ex);
            throw ex;
        }
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
                .orElseThrow(() -> new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Passenger not found."));

        if (passenger.getStatus() != PassengerStatus.ACTIVE) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Account is inactive.");
        }

        if (useCooldown && transactionRepository.existsByPassengerIdAndPaymentMethodAndStatusAndCreatedAtAfter(
                passenger.getId(), paymentMethod(source), TransactionStatus.SUCCESS, LocalDateTime.now().minusSeconds(COOLDOWN_SECONDS))) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT,
                    "CONFLICT", "Payment already processed recently. Please wait a moment.");
        }

        BigDecimal fare = fareFor(passenger);
        String discountType = discountTypeFor(passenger);

        if (passenger.getBalance().compareTo(fare) < 0) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE", "Insufficient balance. Please top up.");
        }

        BigDecimal balanceBefore = passenger.getBalance();
        BigDecimal balanceAfter = balanceBefore.subtract(fare);
        passenger.setBalance(balanceAfter);
        passengerRepository.save(passenger);

        String normalizedPlate = normalizePlate(plateNumber);
        LocalDateTime capturedAt = request == null ? null : com.premier.payment.service.CaptureTime.optional(request.getOfflineCapturedAt());
        DriverShift activeShift = capturedAt == null ? activeShiftForPlate(normalizedPlate)
                : driverShiftRepository.findTopByVehiclePlateNumberAndShiftStartLessThanEqualOrderByShiftStartDesc(normalizedPlate, capturedAt)
                    .filter(shift -> shift.getShiftEnd() == null || !capturedAt.isAfter(shift.getShiftEnd())).orElse(null);
        com.premier.driver.model.Vehicle vehicle = resolveVehicle(device, normalizedPlate, activeShift);
        com.premier.trip.model.VehicleTrip trip = device == null ? null
                : tripService.requireForFare(vehicle == null ? null : vehicle.getId(), capturedAt);
        if (trip != null) {
            vehicle = trip.getVehicle();
            activeShift = trip.getDriverShift();
        }
        String refNumber = source + "-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
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
                .requestFingerprint(request != null ? paymentIdentity.fingerprint(request, device, source) : null)
                .offlineTransactionId(request != null ? clean(request.getOfflineTransactionId()) : null)
                .offlineCapturedAt(capturedAt)
                .deviceId(device != null ? device.deviceId() : null)
                .paymentMethod(paymentMethod(source))
                .vehicle(vehicle)
                .vehiclePlateNumber(normalizedPlate)
                .trip(trip)
                .tripDirection(trip == null ? null : trip.getDirection())
                .originTerminal(trip == null ? null : trip.getOriginTerminal())
                .destinationTerminal(trip == null ? null : trip.getDestinationTerminal())
                .driverShift(activeShift)
                .routeSnapshot(trip != null ? trip.getDirection().routeLabel()
                        : activeShift != null && activeShift.getVehicle() != null ? activeShift.getVehicle().getRoute() : null)
                .requestNonce(request != null ? clean(request.getRequestNonce()) : null)
                .requestTimestamp(request != null ? parseRequestTimestamp(request.getRequestTimestamp()) : null)
                .description(source + " Fare Payment" + (normalizedPlate != null ? " | " + normalizedPlate : ""))
                .build();
        transactionRepository.save(tx);
        realtimeEventPublisher.adminAndPassenger(passenger.getId(), "FARE_PAID", "TRANSACTION", tx.getId());
        farePaymentAttemptService.recordSuccess(tx, paymentMethod(source), rfidUid, normalizedPlate, request);

        paymentNotifications.enqueue(passenger.getId(), refNumber, "FARE");


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

        tx.setResponseSnapshot(paymentIdentity.snapshot(data));
        transactionRepository.save(tx);
        log.info("Fare payment committed intent prepared: method={}, reference={}", source, refNumber);

        return ApiResponse.success("Fare deducted successfully!", data);
    }

    private DriverShift activeShiftForPlate(String plateNumber) {
        if (plateNumber == null) {
            return null;
        }
        return driverShiftRepository.findByVehiclePlateNumberAndStatus(plateNumber, ShiftStatus.ACTIVE)
                .orElse(null);
    }

    private com.premier.driver.model.Vehicle resolveVehicle(DevicePrincipal device, String plateNumber,
                                                              DriverShift activeShift) {
        if (activeShift != null && activeShift.getVehicle() != null) {
            return activeShift.getVehicle();
        }
        if (device != null && device.vehicleId() != null) {
            return vehicleRepository.findById(device.vehicleId())
                    .filter(vehicle -> plateNumber != null
                            && plateNumber.equalsIgnoreCase(vehicle.getPlateNumber()))
                    .orElse(null);
        }
        return plateNumber == null ? null : vehicleRepository.findByPlateNumber(plateNumber).orElse(null);
    }

    private PaymentMethod paymentMethod(String source) {
        if (source == null) return PaymentMethod.RFID;
        return switch (source.trim().toUpperCase()) {
            case "QR" -> PaymentMethod.QR;
            case "NFC" -> PaymentMethod.NFC;
            default -> PaymentMethod.RFID;
        };
    }

    private void recordPaymentFailure(PaymentMethod method, Long passengerId, String rfidUid,
                                      String plateNumber, String deviceId, DeviceFareRequest request,
                                      RuntimeException ex) {
        var reason = farePaymentAttemptService.classifyFailure(ex.getMessage());
        // Keep only safe metadata; never retain a QR/NFC credential or an internal exception message.
        DeviceFareRequest metadata = new DeviceFareRequest();
        if (request != null) {
            metadata.setRequestNonce(request.getRequestNonce());
            metadata.setRequestTimestamp(request.getRequestTimestamp());
        }
        paymentFailureRecorder.afterTransaction(() -> farePaymentAttemptService.recordFailure(
                method,
                passengerId,
                rfidUid,
                plateNumber,
                deviceId,
                metadata,
                null,
                reason,
                reason.name()));
    }

    private void validateDevicePaymentRequest(DeviceFareRequest request, DevicePrincipal device) {
        requireDeviceRequest(request);
        deviceService.requirePlateAssignment(device, request.getPlateNumber());
        deviceService.validateFreshNonce(device, request.getRequestNonce(), request.getRequestTimestamp());
        if (idempotencyKey(request).length() < 12) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request ID is invalid.");
        }
    }

    private void requireDeviceRequest(DeviceFareRequest request) {
        if (request == null) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Payment request is required.");
        }
    }

    private String idempotencyKey(DeviceFareRequest request) {
        String key = clean(request.getIdempotencyKey());
        if (key == null) key = clean(request.getRequestId());
        if (key == null) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request ID is required.");
        }
        return key;
    }

    private java.util.Optional<Transaction> findExistingTransaction(DeviceFareRequest request, String key,
                                                                    DevicePrincipal device, String method) {
        deviceService.lockPaymentDevice(device);
        deviceService.requirePlateAssignment(device, request.getPlateNumber());
        if (key.length() < 12 || key.length() > 120) throw paymentIdentity.conflict();
        paymentIdentity.claim(key, request, device, method);
        String offlineId = clean(request.getOfflineTransactionId());
        if (offlineId != null && offlineId.length() > 120) throw paymentIdentity.conflict();
        var existing = offlineId == null ? java.util.Optional.<Transaction>empty()
                : transactionRepository.findByOfflineTransactionId(offlineId);
        if (existing.isEmpty()) existing = transactionRepository.findByIdempotencyKey(key);
        existing.ifPresent(tx -> {
            if (!key.equals(tx.getIdempotencyKey())) throw paymentIdentity.conflict();
            paymentIdentity.verify(tx.getDeviceId(), tx.getRequestFingerprint(), request, device, method);
        });
        return existing;
    }

    private java.util.Optional<FareQrToken> lockedToken(String hash) {
        // Read only the scalar owner first; never cache token state before acquiring the wallet lock.
        var owner = fareQrTokenRepository.findPassengerIdByTokenHash(hash);
        if (owner.isEmpty()) return java.util.Optional.empty();
        passengerRepository.findLockedById(owner.get()).orElseThrow();
        return fareQrTokenRepository.findLockedByTokenHash(hash);
    }

    private void requirePurpose(FareQrToken token, String purpose) {
        if (!purpose.equals(token.getPurpose())) throw paymentIdentity.conflict();
    }

    private LocalDateTime parseOptionalRequestTimestamp(String timestamp) {
        try {
            return parseRequestTimestamp(timestamp);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private LocalDateTime parseRequestTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.parse(timestamp.trim()), ZoneId.of("Asia/Manila"));
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

    private LocalDateTime nowManila() {
        return LocalDateTime.now(ZoneId.of("Asia/Manila"));
    }

    private String normalizeQrPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "QR fare token is required.");
        }

        String trimmed = payload.trim();
        return trimmed.startsWith(QR_PREFIX) ? trimmed.substring(QR_PREFIX.length()) : trimmed;
    }

    private String normalizeMobileNfcPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Mobile NFC fare token is required.");
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


