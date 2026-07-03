package com.premier.service;

import com.premier.rfid.RfidTapRequest;
import com.premier.rfid.RfidTapResponse;
import com.premier.model.Passenger;
import com.premier.model.PassengerStatus;
import com.premier.model.Transaction;
import com.premier.model.TransactionStatus;
import com.premier.model.TransactionType;
import com.premier.repository.PassengerRepository;
import com.premier.repository.TransactionRepository;
import com.premier.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class RfidTapService {

    // Fixed fare 
    private static final BigDecimal FIXED_FARE       = new BigDecimal("60.00");
    private static final long       COOLDOWN_SECONDS = 3L;

    // Repositories
    private final PassengerRepository   passengerRepository;
    private final TransactionRepository transactionRepository;
    private final FirebaseService firebaseService;


    private final Map<String, LocalDateTime> cooldownMap = new ConcurrentHashMap<>();

    // Public API 

    @Transactional
    public ApiResponse<RfidTapResponse> processTap(RfidTapRequest request) {

        String rfidUid = request.getRfidUid().trim().toUpperCase();

        // Find passenger by RFID UID
        Passenger passenger = passengerRepository
                .findByRfidUid(rfidUid)
                .orElse(null);

        if (passenger == null) {
            log.warn("RFID tap: card not found [uid={}]", mask(rfidUid));
            return ApiResponse.error("Card not found. Please register your RFID card.");
        }

        // Validate card status
        if (passenger.getStatus() == PassengerStatus.BLOCKED) {
            log.warn("RFID tap: card blocked [uid={}]", mask(rfidUid));
            return ApiResponse.error("Your card is blocked. Please contact support.");
        }

        if (passenger.getStatus() != PassengerStatus.ACTIVE) {
            log.warn("RFID tap: card not active [uid={}, status={}]",
                    mask(rfidUid), passenger.getStatus());
            return ApiResponse.error("Your card is not active. Please contact support.");
        }

        // Cooldown protection — prevent rapid duplicate taps (3-second window)
        LocalDateTime lastTap = cooldownMap.get(rfidUid);
        if (lastTap != null &&
                lastTap.plusSeconds(COOLDOWN_SECONDS).isAfter(LocalDateTime.now())) {
            log.info("RFID tap: cooldown active [uid={}]", mask(rfidUid));
            return ApiResponse.error("Tap too fast. Please wait a moment and try again.");
        }

        // Check balance
        BigDecimal balanceBefore = passenger.getBalance();
        if (balanceBefore.compareTo(FIXED_FARE) < 0) {
            log.info("RFID tap: insufficient balance [uid={}, balance={}]",
                    mask(rfidUid), balanceBefore);
            return ApiResponse.error(
                    String.format("Insufficient balance. Current: ₱%.2f, Required: ₱%.2f",
                            balanceBefore, FIXED_FARE));
        }

        // Deduct fare
        BigDecimal balanceAfter = balanceBefore.subtract(FIXED_FARE);
        passenger.setBalance(balanceAfter);
        passengerRepository.save(passenger);

        // Register cooldown timestamp AFTER successful deduction
        cooldownMap.put(rfidUid, LocalDateTime.now());

        // Save transaction record
        String refNumber = "RFID-" +
                UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, 8)
                    .toUpperCase();

        LocalDateTime now = LocalDateTime.now();

        Transaction transaction = Transaction.builder()
                .passenger(passenger)
                .type(TransactionType.FARE_DEDUCTION)
                .status(TransactionStatus.COMPLETED)
                .amount(FIXED_FARE)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .referenceNumber(refNumber)
                .description("RFID Tap - Premier Transit Fixed Fare")
                .build();

        transactionRepository.save(transaction);

        if (passenger.getFcmToken() != null) {
            firebaseService.sendFareDeduction(
                    passenger.getFcmToken(),
                    FIXED_FARE.toString(),
                    balanceAfter.toString(),
                    "RFID");
        }

        // Build and return response
        RfidTapResponse data = RfidTapResponse.builder()
                .cardNumber(mask(passenger.getCardNumber()))
                .rfidUid(null)
                .deductedFare(FIXED_FARE)
                .remainingBalance(balanceAfter)
                .referenceNumber(refNumber)
                .timestamp(now)
                .build();

        log.info("RFID tap success [uid={}, ref={}, {}→{}]",
                mask(rfidUid), refNumber, balanceBefore, balanceAfter);

        return ApiResponse.success("Fare deducted successfully.", data);
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        int visible = Math.min(4, trimmed.length());
        return "****" + trimmed.substring(trimmed.length() - visible);
    }
}
