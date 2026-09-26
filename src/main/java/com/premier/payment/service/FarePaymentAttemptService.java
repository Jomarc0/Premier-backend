package com.premier.payment.service;

import com.premier.driver.model.Vehicle;
import com.premier.driver.repository.VehicleRepository;
import com.premier.model.Passenger;
import com.premier.model.PaymentMethod;
import com.premier.model.Transaction;
import com.premier.payment.model.FarePaymentAttempt;
import com.premier.payment.model.FarePaymentAttemptStatus;
import com.premier.payment.model.FarePaymentFailureReason;
import com.premier.payment.repository.FarePaymentAttemptRepository;
import com.premier.repository.PassengerRepository;
import com.premier.rfid.DeviceFareRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class FarePaymentAttemptService {

    private final FarePaymentAttemptRepository attemptRepository;
    private final PassengerRepository passengerRepository;
    private final VehicleRepository vehicleRepository;

    @Transactional
    public void recordSuccess(Transaction transaction, PaymentMethod paymentMethod, String rfidUid,
                              String plateNumber, DeviceFareRequest request) {
        if (transaction == null) {
            return;
        }

        Vehicle vehicle = transaction.getVehicle();

        attemptRepository.save(FarePaymentAttempt.builder()
                .passenger(transaction.getPassenger())
                .transaction(transaction)
                .paymentMethod(paymentMethod)
                .status(FarePaymentAttemptStatus.SUCCESS)
                .failureReason(FarePaymentFailureReason.NONE)
                .amount(transaction.getAmount())
                .maskedRfidUid(mask(rfidUid))
                .deviceId(transaction.getDeviceId())
                .vehicle(vehicle)
                .vehiclePlateNumber(transaction.getVehiclePlateNumber())
                .tripDirection(transaction.getTripDirection())
                .originTerminal(transaction.getOriginTerminal())
                .destinationTerminal(transaction.getDestinationTerminal())
                .routeSnapshot(transaction.getRouteSnapshot())
                .requestNonce(request != null ? clean(request.getRequestNonce()) : null)
                .requestTimestamp(request != null ? parseRequestTimestamp(request.getRequestTimestamp()) : null)
                .offlineCapturedAt(transaction.getOfflineCapturedAt())
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(PaymentMethod paymentMethod, Long passengerId, String rfidUid,
                              String plateNumber, String deviceId, DeviceFareRequest request,
                              BigDecimal amount, FarePaymentFailureReason reason, String message) {
        Vehicle vehicle = resolveVehicle(plateNumber);
        LocalDateTime capturedAt = safeCapturedAt(request);
        String route = vehicle == null ? null : clean(vehicle.getRoute());
        com.premier.trip.model.TripDirection direction = directionFor(route);

        attemptRepository.save(FarePaymentAttempt.builder()
                .passenger(passengerId == null ? null : passengerRepository.findById(passengerId).orElse(null))
                .paymentMethod(paymentMethod)
                .status(FarePaymentAttemptStatus.FAILED)
                .failureReason(reason == null ? FarePaymentFailureReason.UNKNOWN : reason)
                .amount(amount)
                .maskedRfidUid(mask(rfidUid))
                .deviceId(deviceId)
                .vehicle(vehicle)
                .vehiclePlateNumber(vehicle == null ? normalizePlate(plateNumber) : vehicle.getPlateNumber())
                .tripDirection(direction)
                .originTerminal(direction == null ? null : direction.origin())
                .destinationTerminal(direction == null ? null : direction.destination())
                .routeSnapshot(route)
                .requestNonce(request != null ? clean(request.getRequestNonce()) : null)
                .requestTimestamp(request != null ? parseRequestTimestamp(request.getRequestTimestamp()) : null)
                .offlineCapturedAt(capturedAt)
                .failureMessage(truncate(message, 240))
                .build());
    }

    public FarePaymentFailureReason classifyFailure(String message) {
        String value = message == null ? "" : message.toLowerCase();
        if (value.contains("insufficient balance")) return FarePaymentFailureReason.INSUFFICIENT_BALANCE;
        if (value.contains("blocked")) return FarePaymentFailureReason.BLOCKED_CARD;
        if (value.contains("inactive") || value.contains("frozen") || value.contains("account is")) return FarePaymentFailureReason.INACTIVE_ACCOUNT;
        if (value.contains("already processed") || value.contains("duplicate") || value.contains("too fast")) return FarePaymentFailureReason.DUPLICATE_REQUEST;
        if (value.contains("expired")) return FarePaymentFailureReason.EXPIRED_TOKEN;
        if (value.contains("already been used") || value.contains("used")) return FarePaymentFailureReason.USED_TOKEN;
        if (value.contains("invalid") && value.contains("token")) return FarePaymentFailureReason.INVALID_TOKEN;
        if (value.contains("not recognized") || value.contains("not found") || value.contains("register")) return FarePaymentFailureReason.INVALID_CARD;
        if (value.contains("device") || value.contains("request id") || value.contains("nonce")) return FarePaymentFailureReason.DEVICE_VALIDATION_FAILED;
        if (value.contains("full capacity")) return FarePaymentFailureReason.VEHICLE_FULL;
        if (value.contains("required")) return FarePaymentFailureReason.INVALID_REQUEST;
        return FarePaymentFailureReason.UNKNOWN;
    }

    private Vehicle resolveVehicle(String plateNumber) {
        String plate = normalizePlate(plateNumber);
        return plate == null ? null : vehicleRepository.findByPlateNumber(plate).orElse(null);
    }

    private com.premier.trip.model.TripDirection directionFor(String route) {
        if (route == null) return null;
        String normalized = route.toLowerCase().replace("sm lipa", "sm terminal")
                .replace("→", " to ").replace("->", " to ").replace("_", " ")
                .replaceAll("\\s+", " ").trim();
        if (normalized.startsWith("sm terminal") && normalized.contains("grand terminal")) {
            return com.premier.trip.model.TripDirection.SM_TO_GRAND;
        }
        if (normalized.startsWith("grand terminal") && normalized.contains("sm terminal")) {
            return com.premier.trip.model.TripDirection.GRAND_TO_SM;
        }
        return null;
    }

    private LocalDateTime parseRequestTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(timestamp.trim()), ZoneId.of("Asia/Manila"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalDateTime safeCapturedAt(DeviceFareRequest request) {
        if (request == null) return null;
        try {
            return com.premier.payment.service.CaptureTime.optional(request.getOfflineCapturedAt());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String normalizePlate(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private String clean(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        int visible = Math.min(4, trimmed.length());
        return "*".repeat(Math.max(0, trimmed.length() - visible)) + trimmed.substring(trimmed.length() - visible);
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}



