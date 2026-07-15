package com.premier.staffcash.service;

import com.premier.admin.model.Admin;
import com.premier.admin.model.AdminRole;
import com.premier.admin.repository.AdminRepository;
import com.premier.device.security.DevicePrincipal;
import com.premier.device.service.DeviceService;
import com.premier.driver.model.DriverShift;
import com.premier.driver.model.ShiftStatus;
import com.premier.driver.repository.DriverShiftRepository;
import com.premier.repository.PassengerRepository;
import com.premier.response.ApiResponse;
import com.premier.response.FarePaymentResponse;
import com.premier.rfid.DeviceFareRequest;
import com.premier.staffcash.model.*;
import com.premier.staffcash.repository.StaffCashCardRepository;
import com.premier.staffcash.repository.StaffCashTransactionRepository;
import com.premier.staffcash.request.RegisterStaffCashCardRequest;
import com.premier.staffcash.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StaffCashFareService {
    private final StaffCashCardRepository cardRepository;
    private final StaffCashTransactionRepository transactionRepository;
    private final AdminRepository adminRepository;
    private final PassengerRepository passengerRepository;
    private final DriverShiftRepository shiftRepository;
    private final DeviceService deviceService;

    @Value("${fare.fixed-amount:60.00}")
    private BigDecimal regularFare;

    @Value("${fare.discount-rate:0.20}")
    private BigDecimal discountRate;

    public boolean isStaffCashCard(String uid) {
        return findCard(uid).isPresent();
    }

    @Transactional
    public ApiResponse<FarePaymentResponse> process(DeviceFareRequest request, DevicePrincipal device) {
        String uid = normalizeUid(request != null ? request.getRfidUid() : null);
        StaffCashCard card = findCard(uid)
                .orElseThrow(() -> new RuntimeException("Staff cash card not found."));
        if (card.getStatus() != StaffCashCardStatus.ACTIVE) {
            throw new RuntimeException("Staff cash card is " + card.getStatus().name().toLowerCase() + ".");
        }
        Admin staff = card.getStaff();
        if (!Boolean.TRUE.equals(staff.getActive()) || staff.getRole() != AdminRole.STAFF) {
            throw new RuntimeException("Assigned staff account is inactive.");
        }
        String key = idempotencyKey(request);
        String offlineId = clean(request.getOfflineTransactionId());
        if (offlineId != null) {
            var existing = transactionRepository.findByOfflineTransactionId(offlineId);
            if (existing.isPresent()) {
                return ApiResponse.success("Offline cash fare already synchronized.", toDeviceResponse(existing.get()));
            }
        }
        return transactionRepository.findByIdempotencyKey(key)
                .map(tx -> ApiResponse.success("Cash fare already recorded.", toDeviceResponse(tx)))
                .orElseGet(() -> createTransaction(request, device, card, key));
    }

    private ApiResponse<FarePaymentResponse> createTransaction(DeviceFareRequest request, DevicePrincipal device,
                                                                StaffCashCard card, String key) {
        if (device == null) throw new RuntimeException("Device authentication required.");
        deviceService.requirePlateAssignment(device, request.getPlateNumber());
        deviceService.validateFreshNonce(device, request.getRequestNonce(), request.getRequestTimestamp());

        String plate = normalizePlate(request.getPlateNumber());
        LocalDateTime offlineCapturedAt = parseTimestamp(request.getOfflineCapturedAt());
        DriverShift shift = shiftRepository.findByVehiclePlateNumberAndStatus(plate, ShiftStatus.ACTIVE)
                .or(() -> offlineCapturedAt == null ? java.util.Optional.empty()
                        : shiftRepository.findTopByVehiclePlateNumberAndShiftStartLessThanEqualOrderByShiftStartDesc(
                                plate, offlineCapturedAt)
                                .filter(row -> row.getShiftEnd() == null || !offlineCapturedAt.isAfter(row.getShiftEnd())))
                .orElseThrow(() -> new RuntimeException("Vehicle has no matching driver shift for this fare."));

        BigDecimal discount = card.getPurpose() == StaffCashCardPurpose.DISCOUNTED_CASH
                ? regularFare.multiply(discountRate)
                : BigDecimal.ZERO;
        BigDecimal finalFare = regularFare.subtract(discount);
        String route = shift.getVehicle().getRoute();

        StaffCashTransaction tx = StaffCashTransaction.builder()
                .staff(card.getStaff())
                .operationCard(card)
                .vehicle(shift.getVehicle())
                .driverShift(shift)
                .deviceId(device.deviceId())
                .fareCategory(card.getPurpose())
                .baseFare(regularFare)
                .discountAmount(discount)
                .finalFare(finalFare)
                .referenceNumber("CASH-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase())
                .idempotencyKey(key)
                .offlineTransactionId(clean(request.getOfflineTransactionId()))
                .offlineCapturedAt(offlineCapturedAt)
                .routeSnapshot(route)
                .terminalSnapshot(originTerminal(route))
                .requestTimestamp(parseTimestamp(request.getRequestTimestamp()))
                .build();
        transactionRepository.save(tx);
        return ApiResponse.success("Cash fare recorded.", toDeviceResponse(tx));
    }

    @Transactional
    public ApiResponse<StaffCashCardResponse> register(Admin registeredBy, RegisterStaffCashCardRequest request) {
        Admin staff = adminRepository.findById(request.getStaffId())
                .orElseThrow(() -> new RuntimeException("Staff account not found."));
        if (staff.getRole() != AdminRole.STAFF) throw new RuntimeException("Selected account is not a staff account.");
        String uid = normalizeUid(request.getRfidUid());
        if (uid.length() < 4) throw new RuntimeException("Invalid RFID UID.");
        if (passengerRepository.existsByRfidUid(uid)) {
            throw new RuntimeException("This RFID UID is already registered to a passenger.");
        }
        findCard(uid).ifPresent(existing -> {
            if (!existing.getStaff().getId().equals(staff.getId()) || existing.getPurpose() != request.getPurpose()) {
                throw new RuntimeException("This RFID UID is already registered as another staff cash card.");
            }
        });

        StaffCashCard card = cardRepository.findByStaffIdAndPurpose(staff.getId(), request.getPurpose())
                .orElseGet(StaffCashCard::new);
        card.setStaff(staff);
        card.setRfidUid(uid);
        card.setPurpose(request.getPurpose());
        card.setStatus(StaffCashCardStatus.ACTIVE);
        card.setRegisteredBy(registeredBy);
        card.setRegisteredAt(LocalDateTime.now());
        return ApiResponse.success("Staff cash card registered.", toCardResponse(cardRepository.save(card)));
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<StaffCashCardResponse>> listCards() {
        return ApiResponse.success("Staff cash cards fetched.",
                cardRepository.findAllByOrderByRegisteredAtDesc().stream().map(this::toCardResponse).toList());
    }

    @Transactional
    public ApiResponse<StaffCashCardResponse> changeStatus(Long id, StaffCashCardStatus status) {
        StaffCashCard card = cardRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Staff cash card not found."));
        card.setStatus(status);
        return ApiResponse.success("Staff cash card status updated.", toCardResponse(cardRepository.save(card)));
    }

    @Transactional(readOnly = true)
    public ApiResponse<StaffCashTodayResponse> today(Admin staff) {
        LocalDate date = LocalDate.now();
        List<StaffCashTransaction> rows = transactionRepository
                .findByStaffIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                        staff.getId(), date.atStartOfDay(), date.plusDays(1).atStartOfDay());
        long regular = rows.stream().filter(tx -> tx.getFareCategory() == StaffCashCardPurpose.REGULAR_CASH).count();
        long discounted = rows.size() - regular;
        BigDecimal expected = rows.stream().map(StaffCashTransaction::getFinalFare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return ApiResponse.success("Today's staff cash transactions fetched.", StaffCashTodayResponse.builder()
                .date(date).regularCount(regular).discountedCount(discounted)
                .totalPassengers(rows.size()).expectedCash(expected)
                .transactions(rows.stream().map(this::toItem).toList()).build());
    }

    private FarePaymentResponse toDeviceResponse(StaffCashTransaction tx) {
        return FarePaymentResponse.builder()
                .cardNumber(null).rfidUid(null).baseFare(tx.getBaseFare())
                .deductedFare(tx.getFinalFare()).discountType(tx.getFareCategory().name())
                .referenceNumber(tx.getReferenceNumber()).source("CASH")
                .plateNumber(tx.getVehicle().getPlateNumber()).timestamp(tx.getCreatedAt()).build();
    }

    private StaffCashCardResponse toCardResponse(StaffCashCard card) {
        return StaffCashCardResponse.builder().id(card.getId()).staffId(card.getStaff().getId())
                .staffName(card.getStaff().getFullName()).maskedRfidUid(mask(card.getRfidUid()))
                .purpose(card.getPurpose()).status(card.getStatus()).registeredAt(card.getRegisteredAt()).build();
    }

    private StaffCashTransactionItem toItem(StaffCashTransaction tx) {
        return StaffCashTransactionItem.builder().id(tx.getId()).referenceNumber(tx.getReferenceNumber())
                .plateNumber(tx.getVehicle().getPlateNumber()).deviceId(tx.getDeviceId())
                .route(tx.getRouteSnapshot()).terminal(tx.getTerminalSnapshot())
                .fareCategory(tx.getFareCategory()).finalFare(tx.getFinalFare()).createdAt(tx.getCreatedAt()).build();
    }

    private String idempotencyKey(DeviceFareRequest request) {
        if (request == null) throw new RuntimeException("Payment request is required.");
        String key = clean(request.getIdempotencyKey());
        if (key == null) key = clean(request.getRequestId());
        if (key == null || key.length() < 12) throw new RuntimeException("Request ID is invalid.");
        return key;
    }

    private String originTerminal(String route) {
        if (route == null) return null;
        String normalized = route.toLowerCase();
        if (normalized.startsWith("sm terminal")) return "SM Terminal";
        if (normalized.startsWith("grand terminal")) return "Grand Terminal";
        return route.contains(" to ") ? route.substring(0, route.indexOf(" to ")).trim() : null;
    }

    private LocalDateTime parseTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.ofInstant(Instant.parse(value.trim()), ZoneId.of("Asia/Manila"));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String normalizeUid(String value) {
        return value == null ? "" : value.toUpperCase().replace("UID", "").replaceAll("[^A-F0-9]", "");
    }

    private java.util.Optional<StaffCashCard> findCard(String uid) {
        String normalized = normalizeUid(uid);
        return normalized.isBlank()
                ? java.util.Optional.empty()
                : cardRepository.findByNormalizedRfidUid(normalized);
    }

    private String normalizePlate(String value) { return value == null ? null : value.trim().toUpperCase(); }
    private String clean(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private String mask(String value) {
        if (value == null || value.isBlank()) return null;
        int visible = Math.min(4, value.length());
        return "****" + value.substring(value.length() - visible);
    }
}
