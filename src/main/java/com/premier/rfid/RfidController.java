package com.premier.rfid;

import com.premier.driver.model.DriverShift;
import com.premier.driver.model.ShiftStatus;
import com.premier.driver.repository.DriverShiftRepository;
import com.premier.driver.service.DriverService;
import com.premier.response.ApiResponse;
import com.premier.service.FarePaymentService;
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
    private final DriverService driverService;

    private static final double SM_LIPA_LAT = 13.954781;
    private static final double SM_LIPA_LNG = 121.163096;

    @PostMapping("/tap")
    public ResponseEntity<?> tapCard(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(farePaymentService.processRfidPayment(
                    body.get("rfidUid"),
                    body.get("plateNumber")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/qr/process")
    public ResponseEntity<?> processQrFare(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(farePaymentService.processQrPayment(
                    body.get("payload"),
                    body.get("plateNumber")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/nfc/tap")
    public ResponseEntity<?> processNfcTap(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(farePaymentService.processRfidPayment(
                    body.get("rfidUid"),
                    body.get("plateNumber")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/vehicles")
    public ResponseEntity<?> getTerminalVehicles() {
        return ResponseEntity.ok(driverService.getAllVehicles());
    }

    @PostMapping("/driver/gps")
    public ResponseEntity<?> updateDriverGps(@RequestBody Map<String, Object> body) {
        try {
            String plateNumber = ((String) body.get("plateNumber")).toUpperCase().trim();
            Double latitude = Double.valueOf(body.get("latitude").toString());
            Double longitude = Double.valueOf(body.get("longitude").toString());
            if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                throw new IllegalArgumentException("Invalid GPS coordinates");
            }

            driverShiftRepository.findByVehiclePlateNumberAndStatus(plateNumber, ShiftStatus.ACTIVE)
                    .ifPresent(shift -> {
                        shift.setCurrentLatitude(latitude);
                        shift.setCurrentLongitude(longitude);
                        shift.setLastLocationUpdate(LocalDateTime.now());
                        driverShiftRepository.save(shift);

                        double distLipa = calculateDistance(latitude, longitude, SM_LIPA_LAT, SM_LIPA_LNG);
                        log.info("GPS UPDATE: {} -> ({}, {}) | Lipa: {}km",
                                plateNumber, latitude, longitude, String.format("%.1f", distLipa));
                    });

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
}
