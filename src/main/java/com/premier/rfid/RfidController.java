package com.premier.rfid;

import com.premier.driver.model.DriverShift;
import com.premier.driver.model.ShiftStatus;
import com.premier.driver.model.PassengerOnboard;
import com.premier.driver.repository.DriverShiftRepository;
import com.premier.model.*;
import com.premier.repository.*;
import com.premier.response.ApiResponse;
import com.premier.driver.model.OnboardStatus; 
import com.premier.driver.repository.VehicleRepository; 
import com.premier.driver.repository.PassengerOnboardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/rfid")
@RequiredArgsConstructor
@Slf4j
public class RfidController {

    private final PassengerRepository passengerRepository;
    private final TransactionRepository transactionRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverShiftRepository driverShiftRepository;  
    private final PassengerOnboardRepository onboardRepository;

    private static final BigDecimal FIXED_FARE = new BigDecimal("60.00");

    //GPS COORDINATES
    private static final double SM_LIPA_LAT = 13.954781;
    private static final double SM_LIPA_LNG = 121.163096;
    private static final double SM_BATANGAS_LAT = 13.7567;
    private static final double SM_BATANGAS_LNG = 121.0584;
    private static final double GPS_RADIUS_KM = 5.0;
    private static final int GPS_TIMEOUT_MINUTES = 5;

    @PostMapping("/tap")
    @Transactional
    public ResponseEntity<?> tapCard(@RequestBody Map<String, String> body) {
        String rfidUid = body.get("rfidUid");
        String plateNumber = body.get("plateNumber");

        if (rfidUid == null || rfidUid.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("RFID UID is required."));
        }

        Passenger passenger = passengerRepository
                .findByRfidUid(rfidUid.trim().toUpperCase())
                .orElse(null);

        if (passenger == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error("Card not recognized. Please register your card."));
        }

        if (passenger.getStatus() != PassengerStatus.ACTIVE) {
            return ResponseEntity.status(400)
                    .body(ApiResponse.error("Account is " + passenger.getStatus().name().toLowerCase() + "."));
        }

        if (passenger.getBalance().compareTo(FIXED_FARE) < 0) {
            return ResponseEntity.status(400)
                    .body(ApiResponse.error("Insufficient balance. Current: ₱" + passenger.getBalance() + ". Please top up."));
        }

        // Deduct fare
        BigDecimal balanceBefore = passenger.getBalance();
        BigDecimal balanceAfter = balanceBefore.subtract(FIXED_FARE);
        passenger.setBalance(balanceAfter);
        passengerRepository.save(passenger);

        // Save transaction
        String refNumber = "RFID-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Transaction tx = Transaction.builder()
                .passenger(passenger)
                .type(TransactionType.FARE_DEDUCTION)
                .status(TransactionStatus.SUCCESS)
                .amount(FIXED_FARE)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .referenceNumber(refNumber)
                .description("RFID Tap — Fixed Fare" + (plateNumber != null ? " | " + plateNumber.toUpperCase() : ""))
                .build();
        transactionRepository.save(tx);

        // GPS-BASED ONBOARDING
        if (plateNumber != null && !plateNumber.trim().isEmpty()) {
            driverShiftRepository.findByVehiclePlateNumberAndStatus(plateNumber.toUpperCase(), ShiftStatus.ACTIVE)
                    .ifPresent(shift -> {
                        long onboard = onboardRepository.countByShiftIdAndStatus(shift.getId(), OnboardStatus.ONBOARD);

                        if (onboard < shift.getVehicle().getTotalCapacity()) {
                            String dropOffLocation = determineDropOffLocation(shift);
                            
                            PassengerOnboard record = PassengerOnboard.builder()
                                    .shift(shift)
                                    .passenger(passenger)
                                    .dropOffLocation(dropOffLocation)
                                    .fare(FIXED_FARE)
                                    .passengerCount(1)
                                    .status(OnboardStatus.ONBOARD)
                                    .build();
                            onboardRepository.save(record);

                            log.info("PASSENGER ONBOARD: {} | Vehicle: {} | DROP-OFF: {} | GPS: ({}, {})",
                                    passenger.getCardNumber(), plateNumber.toUpperCase(), 
                                    dropOffLocation, shift.getCurrentLatitude(), shift.getCurrentLongitude());
                        } else {
                            log.warn("FULL CAPACITY: Vehicle {} ({} onboard)", plateNumber, onboard);
                        }
                    });
        }

        Map<String, Object> data = new HashMap<>();
        data.put("cardNumber", passenger.getCardNumber());
        data.put("rfidUid", rfidUid.toUpperCase());
        data.put("deductedFare", FIXED_FARE);
        data.put("remainingBalance", balanceAfter);
        data.put("referenceNumber", refNumber);
        data.put("timestamp", LocalDateTime.now());
        data.put("plateNumber", plateNumber != null ? plateNumber.toUpperCase() : null);

        return ResponseEntity.ok(ApiResponse.success("Fare deducted successfully!", data));
    }

    // DRIVER GPS UPDATE ENDPOINT
    @PostMapping("/driver/gps")
    public ResponseEntity<?> updateDriverGps(@RequestBody Map<String, Object> body) {
        try {
            String plateNumber = ((String) body.get("plateNumber")).toUpperCase().trim();
            Double latitude = Double.valueOf(body.get("latitude").toString());
            Double longitude = Double.valueOf(body.get("longitude").toString());

            driverShiftRepository.findByVehiclePlateNumberAndStatus(plateNumber, ShiftStatus.ACTIVE)
                    .ifPresent(shift -> {
                        shift.setCurrentLatitude(latitude);
                        shift.setCurrentLongitude(longitude);
                        shift.setLastLocationUpdate(LocalDateTime.now());
                        driverShiftRepository.save(shift);

                        double distLipa = calculateDistance(latitude, longitude, SM_LIPA_LAT, SM_LIPA_LNG);
                        log.info("📍 GPS UPDATE: {} → ({}, {}) | Lipa: {:.1f}km", 
                                plateNumber, latitude, longitude, distLipa);
                    });

            return ResponseEntity.ok(Map.of("status", "GPS updated", "plateNumber", plateNumber));
        } catch (Exception e) {
            log.error("GPS update failed", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid GPS data"));
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

        // Near Lipa  Send to Batangas
        if (distLipa < GPS_RADIUS_KM) {
            return "SM Batangas";
        }
        // Near Batangas  Send to Lipa
        else if (distBatangas < GPS_RADIUS_KM) {
            return "SM Lipa";
        }

        return "SM Lipa / SM Batangas";
    }

    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        final int R = 6371;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lngDistance = Math.toRadians(lng2 - lng1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}