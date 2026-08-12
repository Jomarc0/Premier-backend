package com.premier.rfid;

import com.premier.driver.model.DriverLocation;
import com.premier.driver.model.DriverShift;
import com.premier.driver.model.ShiftStatus;
import com.premier.driver.model.VehicleStatus;
import com.premier.driver.repository.DriverLocationRepository;
import com.premier.driver.repository.DriverShiftRepository;
import com.premier.driver.repository.VehicleRepository;
import com.premier.device.security.DeviceContext;
import com.premier.device.service.DeviceService;
import com.premier.response.ApiResponse;
import com.premier.service.FarePaymentService;
import com.premier.realtime.RealtimeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/rfid")
@RequiredArgsConstructor
@Slf4j
public class RfidController {

    private final DriverShiftRepository driverShiftRepository;
    private final FarePaymentService farePaymentService;
    private final VehicleRepository vehicleRepository;
    private final DeviceService deviceService;
    private final DriverLocationRepository driverLocationRepository;
    private final RfidUidCaptureService rfidUidCaptureService;
    private final RealtimeEventPublisher realtimeEventPublisher;

    private static final double SM_LIPA_LAT = 13.954781;
    private static final double SM_LIPA_LNG = 121.163096;

    @PostMapping("/tap")
    public ResponseEntity<?> tapCard(@RequestBody DeviceFareRequest request) {
        try {
            return ResponseEntity.ok(farePaymentService.processRfidPayment(
                    request,
                    DeviceContext.get()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/qr/process")
    public ResponseEntity<?> processQrFare(@RequestBody DeviceFareRequest request) {
        try {
            return ResponseEntity.ok(farePaymentService.processQrPayment(
                    request,
                    DeviceContext.get()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/nfc/tap")
    public ResponseEntity<?> processNfcTap(@RequestBody DeviceFareRequest request) {
        try {
            String mobileNfcToken = request.getMobileNfcToken();
            if ((mobileNfcToken != null && !mobileNfcToken.isBlank())
                    || (request.getPayload() != null && !request.getPayload().isBlank())) {
                return ResponseEntity.ok(farePaymentService.processMobileNfcTokenPayment(
                        request,
                        DeviceContext.get()));
            }

            return ResponseEntity.ok(farePaymentService.processRfidPayment(
                    request,
                    DeviceContext.get()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/vehicles")
    public ResponseEntity<?> getTerminalVehicles() {
        return ResponseEntity.ok(ApiResponse.success(
                "Active terminal vehicles fetched.",
                vehicleRepository.findByStatus(VehicleStatus.ACTIVE).stream()
                        .map(RfidVehicleResponse::from).toList()));
    }

    @GetMapping("/registration/uid-request")
    public ResponseEntity<?> getPendingUidCaptureRequest() {
        return ResponseEntity.ok(rfidUidCaptureService.nextForDevice(DeviceContext.get()));
    }

    @PostMapping("/registration/uid-capture")
    public ResponseEntity<?> submitUidCapture(@RequestBody Map<String, Object> body) {
        try {
            deviceService.validateFreshNonce(
                    DeviceContext.get(),
                    (String) body.get("requestNonce"),
                    (String) body.get("requestTimestamp"));
            return ResponseEntity.ok(rfidUidCaptureService.submitFromDevice(
                    (String) body.get("requestId"),
                    (String) body.get("rfidUid"),
                    DeviceContext.get()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/gps")
    public ResponseEntity<?> updateVehicleGps(@RequestBody Map<String, Object> body) {
        try {
            String plateNumber = ((String) body.get("plateNumber")).toUpperCase().trim();
            deviceService.requirePlateAssignment(DeviceContext.get(), plateNumber);
            Double latitude = Double.valueOf(body.get("latitude").toString());
            Double longitude = Double.valueOf(body.get("longitude").toString());
            double speed = optionalTelemetry(body, "speed", 0, 300);
            double heading = optionalTelemetry(body, "heading", 0, 360);
            deviceService.validateFreshNonce(
                    DeviceContext.get(),
                    (String) body.get("requestNonce"),
                    (String) body.get("requestTimestamp"));
            if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                throw new IllegalArgumentException("Invalid GPS coordinates");
            }

            Long shiftId = driverShiftRepository.findByVehiclePlateNumberAndStatus(plateNumber, ShiftStatus.ACTIVE)
                    .map(shift -> {
                        shift.setCurrentLatitude(latitude);
                        shift.setCurrentLongitude(longitude);
                        shift.setLastLocationUpdate(LocalDateTime.now());
                        driverShiftRepository.save(shift);

                        double distLipa = calculateDistance(latitude, longitude, SM_LIPA_LAT, SM_LIPA_LNG);
                        log.info("GPS UPDATE: {} | Lipa: {}km",
                                plateNumber, String.format("%.1f", distLipa));
                        return shift.getId();
                    })
                    .orElse(null);

            DriverLocation location = driverLocationRepository.save(DriverLocation.builder()
                    .plateNumber(plateNumber)
                    .shiftId(shiftId)
                    .latitude(latitude)
                    .longitude(longitude)
                    .speed(speed)
                    .heading(heading)
                    .recordedAt(LocalDateTime.now())
                    .build());
            realtimeEventPublisher.admin("VEHICLE_LOCATION_UPDATED", "VEHICLE_LOCATION", location.getId());
            realtimeEventPublisher.staff("VEHICLE_LOCATION_UPDATED", "VEHICLE_LOCATION", location.getId());

            return ResponseEntity.ok(Map.of("status", "GPS updated", "plateNumber", plateNumber));
        } catch (Exception e) {
            log.error("GPS update failed", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid GPS data"));
        }
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

    private double optionalTelemetry(Map<String, Object> body, String field, double min, double max) {
        Object value = body.get(field);
        if (value == null) return 0.0;
        double number = Double.parseDouble(value.toString());
        if (!Double.isFinite(number) || number < min || number > max) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return number;
    }
}



