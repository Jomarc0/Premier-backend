package com.premier.driver.service;

import com.premier.admin.model.Admin;
import com.premier.admin.model.AdminRole;
import com.premier.admin.repository.AdminRepository;
import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import com.premier.driver.request.DriverLoginRequest;
import com.premier.driver.request.IssueDriverShiftCodeRequest;
import com.premier.driver.request.LocationRequest;
import com.premier.driver.response.DriverLoginResponse;
import com.premier.driver.response.DriverShiftCodeResponse;
import com.premier.driver.response.DriverShiftResponse;
import com.premier.driver.security.DriverJwtUtil;
import com.premier.driver.security.DriverPrincipal;
import com.premier.exception.ClientException;
import com.premier.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DriverPortalService {
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DriverAssignmentRepository assignments;
    private final DriverShiftCodeRepository codes;
    private final DriverShiftRepository shifts;
    private final VehicleRepository vehicles;
    private final DriverLocationRepository locations;
    private final AdminRepository admins;
    private final DriverJwtUtil jwt;
    private final com.premier.trip.service.VehicleTripService tripService;

    @Transactional
    public ApiResponse<DriverShiftCodeResponse> issueCode(Admin principal, IssueDriverShiftCodeRequest request) {
        Admin admin = requireSuperAdmin(principal);
        String plate = normalizePlate(request.plateNumber());
        DriverAssignment assignment = assignments.findByVehiclePlateNumberAndStatus(plate, AssignmentStatus.ACTIVE)
                .orElseThrow(() -> new ClientException(HttpStatus.NOT_FOUND, "ASSIGNMENT_NOT_FOUND", "No active driver assignment exists for this vehicle."));
        Driver driver = assignment.getDriver();
        Vehicle vehicle = assignment.getVehicle();
        if (driver.getStatus() != DriverStatus.ACTIVE || vehicle.getStatus() != VehicleStatus.ACTIVE) {
            throw new ClientException(HttpStatus.CONFLICT, "ASSIGNMENT_INACTIVE", "Only active drivers and vehicles can receive shift codes.");
        }
        LocalDateTime now = LocalDateTime.now();
        codes.expireActiveForAssignment(assignment.getId(), now);
        String rawCode = newCode();
        DriverShiftCode row = DriverShiftCode.builder().assignment(assignment).driver(driver).vehicle(vehicle)
                .codeHash(hash(rawCode)).expiresAt(now.plusMinutes(15)).issuedBy(admin)
                .reason(request.reason().trim()).build();
        codes.save(row);
        return ApiResponse.success("Driver shift code issued. Show it to the verified assigned driver only.",
                new DriverShiftCodeResponse(assignment.getId(), driver.getId(), driver.getFullName(), vehicle.getId(),
                        vehicle.getPlateNumber(), rawCode, row.getExpiresAt()));
    }

    @Transactional
    public ApiResponse<DriverLoginResponse> login(DriverLoginRequest request) {
        String plate = normalizePlate(request.plateNumber());
        DriverShiftCode code = codes.findByCodeHash(hash(normalizeCode(request.shiftCode())))
                .orElseThrow(() -> new ClientException(HttpStatus.UNAUTHORIZED, "INVALID_SHIFT_CODE", "Invalid or expired shift code."));
        LocalDateTime now = LocalDateTime.now();
        if (code.getUsedAt() != null || !code.getExpiresAt().isAfter(now) || !code.getVehicle().getPlateNumber().equals(plate)) {
            throw new ClientException(HttpStatus.UNAUTHORIZED, "INVALID_SHIFT_CODE", "Invalid or expired shift code.");
        }
        DriverAssignment assignment = assignments.findByVehiclePlateNumberAndStatus(plate, AssignmentStatus.ACTIVE)
                .orElseThrow(() -> new ClientException(HttpStatus.UNAUTHORIZED, "ASSIGNMENT_REQUIRED", "Driver is not assigned to this vehicle."));
        if (!assignment.getDriver().getId().equals(code.getDriver().getId()) || !assignment.getVehicle().getId().equals(code.getVehicle().getId())) {
            throw new ClientException(HttpStatus.UNAUTHORIZED, "ASSIGNMENT_REQUIRED", "Driver is not assigned to this vehicle.");
        }
        DriverShift shift = shifts.findByDriverIdAndStatus(code.getDriver().getId(), ShiftStatus.ACTIVE)
                .orElseGet(() -> shifts.save(DriverShift.builder().driver(code.getDriver()).vehicle(code.getVehicle()).status(ShiftStatus.ACTIVE).build()));
        if (!shift.getVehicle().getId().equals(code.getVehicle().getId())) {
            throw new ClientException(HttpStatus.CONFLICT, "ACTIVE_SHIFT_CONFLICT", "End the current shift before starting another vehicle.");
        }
        code.setUsedAt(now);
        codes.save(code);
        String token = jwt.generate(code.getDriver().getId(), shift.getId(), code.getVehicle().getId(), plate, code.getDriver().getFullName());
        return ApiResponse.success("Driver shift started.", new DriverLoginResponse(token, shift.getId(), code.getDriver().getId(),
                code.getDriver().getFullName(), plate, code.getVehicle().getRoute(), code.getVehicle().getTotalCapacity()));
    }

    @Transactional(readOnly = true)
    public ApiResponse<DriverShiftResponse> currentShift(DriverPrincipal principal, String plateNumber) {
        requirePrincipalPlate(principal, plateNumber);
        DriverShift shift = activePrincipalShift(principal);
        return ApiResponse.success("Driver shift fetched.", response(shift));
    }

    @Transactional
    public ApiResponse<Map<String, Object>> location(DriverPrincipal principal, LocationRequest request) {
        requirePrincipalPlate(principal, request.getPlateNumber());
        DriverShift shift = activePrincipalShift(principal);
        if (request.getShiftId() != null && !request.getShiftId().equals(shift.getId())) {
            throw new ClientException(HttpStatus.FORBIDDEN, "SHIFT_MISMATCH", "Location update does not belong to the active shift.");
        }
        LocalDateTime now = LocalDateTime.now();
        DriverLocation saved = locations.save(DriverLocation.builder().plateNumber(shift.getVehicle().getPlateNumber())
                .shiftId(shift.getId()).latitude(request.getLatitude()).longitude(request.getLongitude())
                .speed(safeMotion(request.getSpeed(), 0, 180)).heading(safeMotion(request.getHeading(), 0, 360))
                .recordedAt(now).receivedAt(java.time.Instant.now()).build());
        shift.setCurrentLatitude(saved.getLatitude()); shift.setCurrentLongitude(saved.getLongitude()); shift.setLastLocationUpdate(now);
        shifts.save(shift);
        return ApiResponse.success("Driver location recorded.", Map.of("locationId", saved.getId(), "shiftId", shift.getId()));
    }

    @Transactional
    public ApiResponse<String> endShift(DriverPrincipal principal, String plateNumber) {
        requirePrincipalPlate(principal, plateNumber);
        DriverShift shift = activePrincipalShift(principal);
        tripService.cancelActiveForShift(shift);
        shift.setStatus(ShiftStatus.COMPLETED);
        shift.setShiftEnd(LocalDateTime.now());
        shifts.save(shift);
        return ApiResponse.success("Driver shift ended.", "COMPLETED");
    }

    public ApiResponse<String> dropOff(DriverPrincipal principal, Long onboardId) {
        if (principal == null || onboardId == null) throw new ClientException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Drop-off request is invalid.");
        throw new ClientException(HttpStatus.NOT_IMPLEMENTED, "ONBOARD_MANIFEST_UNAVAILABLE", "Drop-off confirmation requires an onboard passenger manifest table before it can be safely enabled.");
    }

    public ApiResponse<com.premier.trip.response.VehicleTripResponse> startTrip(
            DriverPrincipal principal, com.premier.trip.model.TripDirection direction) {
        return tripService.start(principal, direction);
    }

    public ApiResponse<com.premier.trip.response.VehicleTripResponse> completeTrip(
            DriverPrincipal principal, Long tripId) {
        return tripService.complete(principal, tripId);
    }

    private DriverShiftResponse response(DriverShift shift) {
        int total = shift.getVehicle().getTotalCapacity();
        var activeTrip = tripService.activeForVehicle(shift.getVehicle().getId())
                .map(com.premier.trip.response.VehicleTripResponse::from).orElse(null);
        return new DriverShiftResponse(shift.getId(), shift.getDriver().getId(), shift.getDriver().getFullName(),
                shift.getVehicle().getId(), shift.getVehicle().getPlateNumber(), shift.getVehicle().getRoute(), total,
                0, total, shift.getPassengersServed(), shift.getShiftStart(), activeTrip, List.of());
    }

    private DriverShift activePrincipalShift(DriverPrincipal principal) {
        return shifts.findById(principal.shiftId())
                .filter(s -> s.getStatus() == ShiftStatus.ACTIVE && s.getDriver().getId().equals(principal.driverId())
                        && s.getVehicle().getId().equals(principal.vehicleId()))
                .orElseThrow(() -> new ClientException(HttpStatus.UNAUTHORIZED, "SHIFT_INACTIVE", "Driver shift is no longer active."));
    }

    private void requirePrincipalPlate(DriverPrincipal principal, String plateNumber) {
        if (principal == null || !principal.plateNumber().equals(normalizePlate(plateNumber))) {
            throw new ClientException(HttpStatus.FORBIDDEN, "PLATE_MISMATCH", "Driver session is not authorized for this vehicle.");
        }
    }

    private Admin requireSuperAdmin(Admin principal) {
        if (principal == null) throw new ClientException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Super Admin authorization is required.");
        Admin admin = admins.findById(principal.getId()).orElseThrow(() -> new ClientException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Super Admin authorization is required."));
        if (admin.getRole() != AdminRole.SUPER_ADMIN || !Boolean.TRUE.equals(admin.getActive()) || admin.isLocked()
                || !Boolean.TRUE.equals(admin.getIs2FaEnabled()) || admin.getSessionVersion() != principal.getSessionVersion()) {
            throw new ClientException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Current Super Admin authorization is required.");
        }
        return admin;
    }

    private String newCode() {
        StringBuilder b = new StringBuilder("DRV-");
        for (int i = 0; i < 8; i++) b.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        return b.toString();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String normalizePlate(String value) {
        String plate = value == null ? "" : value.trim().toUpperCase();
        if (plate.isBlank() || plate.length() > 20) throw new ClientException(HttpStatus.BAD_REQUEST, "INVALID_PLATE", "Vehicle plate number is invalid.");
        return plate;
    }

    private String normalizeCode(String value) { return value == null ? "" : value.trim().toUpperCase().replace(" ", ""); }
    private Double safeMotion(Double value, double min, double max) { return value == null || !Double.isFinite(value) || value < min || value > max ? null : value; }
}
