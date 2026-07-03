package com.premier.driver.service;

import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import com.premier.driver.security.DriverJwtUtil;
import com.premier.model.Passenger;
import com.premier.model.PassengerCardCategory;
import com.premier.model.PassengerStatus;
import com.premier.repository.PassengerRepository;
import com.premier.repository.TransactionRepository;
import com.premier.response.ApiResponse;
import com.premier.model.Transaction;
import com.premier.model.TransactionType;
import com.premier.model.TransactionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.stream.Collectors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DriverService {

    private static final String ROUTE_SM_TO_GRAND = "SM Terminal to Grand Terminal";
    private static final String ROUTE_GRAND_TO_SM = "Grand Terminal to SM Terminal";
    private static final double SM_TERMINAL_LAT = 13.954781;
    private static final double SM_TERMINAL_LNG = 121.163096;
    private static final double GRAND_TERMINAL_LAT = 13.790391;
    private static final double GRAND_TERMINAL_LNG = 121.062721;
    private static final double TERMINAL_GEOFENCE_KM = 5.0;
    private static final long GPS_ONLINE_WINDOW_MINUTES = 5;
    private static final double DEFAULT_SPEED_KMH = 30.0;
    private static final BigDecimal FIXED_FARE = new BigDecimal("60.00");

    private final DriverRepository             driverRepository;
    private final VehicleRepository            vehicleRepository;
    private final DriverAssignmentRepository   assignmentRepository;
    private final DriverShiftRepository        shiftRepository;
    private final PassengerOnboardRepository   onboardRepository;
    private final PassengerRepository          passengerRepository;
    private final TransactionRepository        transactionRepository;
    private final DriverJwtUtil                driverJwtUtil;
    private final DriverLocationRepository driverLocationRepository;
    private final DriverOperationalStatusService driverOperationalStatusService;

    //LOGIN

    public ApiResponse<Map<String, Object>> driverLogin(
            String plateNumber) {

        Vehicle vehicle = vehicleRepository
                .findByPlateNumber(plateNumber.toUpperCase())
                .orElseThrow(() -> new RuntimeException(
                        "Vehicle not found: " + plateNumber));

        if (vehicle.getStatus() == VehicleStatus.MAINTENANCE ||
            vehicle.getStatus() == VehicleStatus.OUT_OF_SERVICE) {
            throw new RuntimeException(
                "Vehicle is not available for service.");
        }

        DriverAssignment assignment = assignmentRepository
                .findByVehiclePlateNumberAndStatus(
                        plateNumber.toUpperCase(),
                        AssignmentStatus.ACTIVE)
                .orElseThrow(() -> new RuntimeException(
                        "No active driver assigned to this vehicle."));

        Driver driver = assignment.getDriver();

        Optional<DriverShift> existingShift = shiftRepository
                .findByVehiclePlateNumberAndStatus(
                        plateNumber.toUpperCase(), ShiftStatus.ACTIVE);

        DriverShift shift;
        if (existingShift.isPresent()) {
            shift = existingShift.get();
        } else {
            shift = DriverShift.builder()
                    .driver(driver)
                    .vehicle(vehicle)
                    .status(ShiftStatus.ACTIVE)
                    .build();
            shiftRepository.save(shift);
        }

        vehicle.setStatus(VehicleStatus.ACTIVE);
        vehicleRepository.save(vehicle);

        driver.setStatus(DriverStatus.ACTIVE);
        driverRepository.save(driver);

        // Generate JWT token 
        String token = driverJwtUtil.generateToken(
                vehicle.getPlateNumber(), shift.getId());

        Map<String, Object> data = new HashMap<>();
        data.put("shiftId",       shift.getId());
        data.put("driverId",      driver.getId());
        data.put("driverName",    driver.getFullName());
        data.put("vehicleId",     vehicle.getId());
        data.put("plateNumber",   vehicle.getPlateNumber());
        data.put("route",         vehicle.getRoute());
        data.put("totalCapacity", vehicle.getTotalCapacity());
        data.put("status",        vehicle.getStatus());
        data.put("token",         token);   

        log.info("Driver {} started shift on vehicle {}",
                driver.getFullName(), plateNumber);

        return ApiResponse.success(
                "Shift started successfully.", data);
    }

    // SHIFT INFO

    public ApiResponse<Map<String, Object>> getShiftInfo(
            String plateNumber) {

        DriverShift shift = shiftRepository
                .findByVehiclePlateNumberAndStatus(
                        plateNumber.toUpperCase(), ShiftStatus.ACTIVE)
                .orElseThrow(() -> new RuntimeException(
                        "No active shift found."));

        Vehicle vehicle = shift.getVehicle();
        Driver  driver  = shift.getDriver();

        List<PassengerOnboard> onboardList = onboardRepository
                .findByShiftIdAndStatus(
                        shift.getId(), OnboardStatus.ONBOARD);

        long onboardCount  = onboardList.size();
        long availableSeats = vehicle.getTotalCapacity() - onboardCount;

        List<Map<String, Object>> passengers = new ArrayList<>();
        for (PassengerOnboard p : onboardList) {
            Map<String, Object> pMap = new HashMap<>();
            pMap.put("onboardId",      p.getId());
            pMap.put("userId",         p.getPassenger().getId());
            pMap.put("dropOff",        p.getDropOffLocation());
            pMap.put("fare",           p.getFare());
            pMap.put("passengerCount", p.getPassengerCount());
            pMap.put("status",         p.getStatus());
            pMap.put("boardedAt",      p.getBoardedAt());
            passengers.add(pMap);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("shiftId",           shift.getId());
        data.put("driverName",        driver.getFullName());
        data.put("plateNumber",       vehicle.getPlateNumber());
        data.put("route",             vehicle.getRoute());
        data.put("totalCapacity",     vehicle.getTotalCapacity());
        data.put("passengersOnboard", onboardCount);
        data.put("availableSeats",    availableSeats);
        data.put("vehicleStatus",     vehicle.getStatus());
        data.put("onboardPassengers", passengers);

        return ApiResponse.success("Shift info fetched.", data);
    }

    //  PASSENGER TAP-IN 

    @Transactional
    public ApiResponse<Map<String, Object>> tapIn(
            Long shiftId,
            String rfidUid,
            String dropOffLocation,
            BigDecimal requestedFare,
            int passengerCount) {
        DriverShift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new RuntimeException(
                        "Shift not found."));

        if (shift.getStatus() != ShiftStatus.ACTIVE)
            throw new RuntimeException("Shift is not active.");

        Passenger passenger = passengerRepository
                .findLockedByRfidUid(rfidUid.trim().toUpperCase())
                .orElseThrow(() -> new RuntimeException(
                        "Passenger not found. Please check RFID card."));

        if (passenger.getStatus() != PassengerStatus.ACTIVE)
            throw new RuntimeException(
                "Account is " +
                passenger.getStatus().name().toLowerCase() + ".");

        BigDecimal fare = fareFor(passenger);

        if (passenger.getBalance().compareTo(fare) < 0)
            throw new RuntimeException(
                "Insufficient balance. Current: ₱"
                + passenger.getBalance());

        long onboardCount = onboardRepository
                .countByShiftIdAndStatus(shiftId, OnboardStatus.ONBOARD);

        if (onboardCount >= shift.getVehicle().getTotalCapacity())
            throw new RuntimeException("Vehicle is at full capacity!");

        // Deduct fare
        BigDecimal balanceBefore = passenger.getBalance();
        BigDecimal balanceAfter  = balanceBefore.subtract(fare);
        passenger.setBalance(balanceAfter);
        passengerRepository.save(passenger);

        // Save transaction record
        Transaction tx = new Transaction();
        tx.setPassenger(passenger);
        tx.setType(TransactionType.FARE_DEDUCTION);
        tx.setStatus(TransactionStatus.SUCCESS);
        tx.setAmount(fare);
        tx.setBalanceBefore(balanceBefore);
        tx.setBalanceAfter(balanceAfter);
        tx.setDescription("Fare deduction - " + dropOffLocation);
        tx.setReferenceNumber("FARE-" +
            UUID.randomUUID().toString()
                .substring(0, 8).toUpperCase());
        transactionRepository.save(tx);

        // Record boarding
        PassengerOnboard onboard = PassengerOnboard.builder()
                .shift(shift)
                .passenger(passenger)
                .dropOffLocation(dropOffLocation)
                .fare(fare)
                .passengerCount(passengerCount)
                .status(OnboardStatus.ONBOARD)
                .build();
        onboardRepository.save(onboard);

        log.info("Passenger {} tapped in. Drop-off: {}",
                passenger.getId(), dropOffLocation);

        Map<String, Object> result = new HashMap<>();
        result.put("onboardId",     onboard.getId());
        result.put("userId",        passenger.getId());
        result.put("dropOff",       dropOffLocation);
        result.put("fare",          fare);
        result.put("balanceBefore", balanceBefore);
        result.put("balanceAfter",  balanceAfter);
        result.put("status",        "ONBOARD");

        return ApiResponse.success(
                "Tap-in successful! Fare deducted.", result);
    }

    private BigDecimal fareFor(Passenger passenger) {
        if (Boolean.TRUE.equals(passenger.getDiscountEligible())
                && passenger.getCardCategory() != null
                && passenger.getCardCategory() != PassengerCardCategory.REGULAR) {
            return FIXED_FARE.multiply(new BigDecimal("0.80"));
        }
        return FIXED_FARE;
    }

    //  DROP-OFF 

    @Transactional
    public ApiResponse<Map<String, Object>> dropOff(Long onboardId) {

        PassengerOnboard onboard = onboardRepository
                .findById(onboardId)
                .orElseThrow(() -> new RuntimeException(
                        "Onboard record not found."));

        if (onboard.getStatus() != OnboardStatus.ONBOARD)
            throw new RuntimeException(
                "Passenger is not currently onboard.");

        onboard.setStatus(OnboardStatus.DROPPED_OFF);
        onboard.setDroppedOffAt(LocalDateTime.now());
        onboardRepository.save(onboard);

        DriverShift shift = onboard.getShift();
        shift.setPassengersServed(
                shift.getPassengersServed() + 1);
        shiftRepository.save(shift);

        log.info("Passenger {} dropped off at {}",
                onboard.getPassenger().getId(),
                onboard.getDropOffLocation());

        Map<String, Object> result = new HashMap<>();
        result.put("onboardId", onboardId);
        result.put("userId",    onboard.getPassenger().getId());
        result.put("dropOff",   onboard.getDropOffLocation());
        result.put("status",    "DROPPED_OFF");

        return ApiResponse.success("Drop-off confirmed.", result);
    }

    //  END SHIFT 

    @Transactional
    public ApiResponse<Map<String, Object>> endShift(
            String plateNumber) {

        DriverShift shift = shiftRepository
                .findByVehiclePlateNumberAndStatus(
                        plateNumber.toUpperCase(), ShiftStatus.ACTIVE)
                .orElseThrow(() -> new RuntimeException(
                        "No active shift found."));

        long stillOnboard = onboardRepository
                .countByShiftIdAndStatus(
                        shift.getId(), OnboardStatus.ONBOARD);

        if (stillOnboard > 0)
            throw new RuntimeException(
                "Cannot end shift. " + stillOnboard
                + " passenger(s) still onboard!");

        shift.setStatus(ShiftStatus.COMPLETED);
        shift.setShiftEnd(LocalDateTime.now());
        shiftRepository.save(shift);

        Vehicle vehicle = shift.getVehicle();
        vehicle.setStatus(VehicleStatus.INACTIVE);
        vehicleRepository.save(vehicle);

        Driver driver = shift.getDriver();
        driver.setStatus(DriverStatus.INACTIVE);
        driverRepository.save(driver);

        Map<String, Object> result = new HashMap<>();
        result.put("shiftId",          shift.getId());
        result.put("driverName",       driver.getFullName());
        result.put("plateNumber",      vehicle.getPlateNumber());
        result.put("passengersServed", shift.getPassengersServed());
        result.put("shiftStart",       shift.getShiftStart());
        result.put("shiftEnd",         shift.getShiftEnd());

        return ApiResponse.success(
                "Shift ended successfully.", result);
    }
    //  UPDATE GPS 

    @Transactional
    public ApiResponse<String> updateGps(
            String plateNumber, Double latitude, Double longitude) {
        if (latitude == null || latitude < -90 || latitude > 90 ||
            longitude == null || longitude < -180 || longitude > 180) {
            throw new RuntimeException("Invalid GPS coordinates.");
        }

        Vehicle vehicle = vehicleRepository
                .findByPlateNumber(plateNumber.toUpperCase())
                .orElseThrow(() -> new RuntimeException(
                        "Vehicle not found."));

        vehicle.setLatitude(latitude);
        vehicle.setLongitude(longitude);
        vehicleRepository.save(vehicle);

        return ApiResponse.success("GPS updated.", "OK");
    }

    // LIST HELPERS 

    public ApiResponse<List<Vehicle>> getAllVehicles() {
        driverOperationalStatusService.reconcileStaleOperations();
        return ApiResponse.success("Vehicles fetched.",
                vehicleRepository.findAll());
    }

    public ApiResponse<List<Driver>> getAllDrivers() {
        driverOperationalStatusService.reconcileStaleOperations();
        return ApiResponse.success("Drivers fetched.",
                driverRepository.findAll());
    }
    // MAP DATA 
    public ApiResponse<List<Map<String, Object>>> getBusLocations() {
        driverOperationalStatusService.reconcileStaleOperations();
        List<Map<String, Object>> buses = new ArrayList<>();

        //Get ALL latest locations per plate (admin map)
        List<DriverLocation> latestLocations = driverLocationRepository.findLatestPerPlate();
        
        Map<String, DriverLocation> latestLocMap = latestLocations.stream()
            .collect(Collectors.toMap(
                DriverLocation::getPlateNumber,
                loc -> loc,
                (existing, replacement) -> replacement
            ));

        vehicleRepository.findAll().forEach(vehicle -> {
            var shiftOpt = shiftRepository
                .findByVehiclePlateNumberAndStatus(
                    vehicle.getPlateNumber(),
                    ShiftStatus.ACTIVE);

            String driverName = "No Driver";
            int onboard = 0;
            Long shiftId = null;

            if (shiftOpt.isPresent()) {
                var shift = shiftOpt.get();
                driverName = shift.getDriver().getFullName();
                shiftId = shift.getId();
                onboard = (int) onboardRepository
                    .countByShiftIdAndStatus(shiftId, OnboardStatus.ONBOARD);
            }

            //Get latest location for this vehicle
            DriverLocation latestLoc = latestLocMap.get(vehicle.getPlateNumber());
            boolean hasFreshLocation = isFreshLocation(latestLoc);
            Double lat = hasFreshLocation ? latestLoc.getLatitude() : vehicle.getLatitude();
            Double lng = hasFreshLocation ? latestLoc.getLongitude() : vehicle.getLongitude();

            Map<String, Object> bus = new HashMap<>();
            bus.put("vehicleId", vehicle.getId());
            bus.put("plateNumber", vehicle.getPlateNumber());
            bus.put("driverName", driverName);

            // Default fallback locations
            if (vehicle.getRoute() != null &&
                vehicle.getRoute().toUpperCase().contains("LIPA")) {

                // SM Lipa fallback
                bus.put("latitude", lat != null ? lat : SM_TERMINAL_LAT);
                bus.put("longitude", lng != null ? lng : SM_TERMINAL_LNG);

            } else {

                // Grand Terminal fallback
                bus.put("latitude", lat != null ? lat : GRAND_TERMINAL_LAT);
                bus.put("longitude", lng != null ? lng : GRAND_TERMINAL_LNG);
            }

            String routeDirection = routeForLocation(lat, lng, vehicle.getRoute());
            double speedKmh = hasFreshLocation && latestLoc.getSpeed() != null && latestLoc.getSpeed() > 0
                    ? latestLoc.getSpeed()
                    : DEFAULT_SPEED_KMH;
            double distanceRemainingKm = distanceToDestinationKm(lat, lng, routeDirection);
            String displayStatus = vehicle.getStatus() == VehicleStatus.ACTIVE && !hasFreshLocation
                    ? VehicleStatus.INACTIVE.name()
                    : vehicle.getStatus().name();

            bus.put("route", routeDirection);
            bus.put("distanceRemainingKm", round1(distanceRemainingKm));
            bus.put("estimatedArrivalMinutes", Math.max(1L, Math.round((distanceRemainingKm / speedKmh) * 60.0)));
            bus.put("status", displayStatus);
            bus.put("online", hasFreshLocation);
            bus.put("locationFresh", hasFreshLocation);
            bus.put("passengersOnboard", onboard);
            bus.put("totalCapacity", vehicle.getTotalCapacity());
            bus.put("availableSeats", vehicle.getTotalCapacity() - onboard);
            bus.put("lastUpdated", latestLoc != null ? latestLoc.getRecordedAt() : null);
            bus.put("speed", hasFreshLocation ? latestLoc.getSpeed() : 0.0);
            
            buses.add(bus);
        });

        return ApiResponse.success("Bus locations fetched.", buses);
    }

    private boolean isFreshLocation(DriverLocation location) {
        return location != null
                && location.getRecordedAt() != null
                && location.getRecordedAt().isAfter(LocalDateTime.now().minusMinutes(GPS_ONLINE_WINDOW_MINUTES));
    }

    private String routeForLocation(Double latitude, Double longitude, String storedRoute) {
        if (latitude != null && longitude != null) {
            double distanceToSm = distanceKm(latitude, longitude, SM_TERMINAL_LAT, SM_TERMINAL_LNG);
            double distanceToGrand = distanceKm(latitude, longitude, GRAND_TERMINAL_LAT, GRAND_TERMINAL_LNG);

            if (distanceToSm <= TERMINAL_GEOFENCE_KM && distanceToSm <= distanceToGrand) {
                return ROUTE_GRAND_TO_SM;
            }
            if (distanceToGrand <= TERMINAL_GEOFENCE_KM) {
                return ROUTE_SM_TO_GRAND;
            }
        }

        String normalizedRoute = normalizeRoute(storedRoute);
        if (normalizedRoute.equals(normalizeRoute(ROUTE_GRAND_TO_SM))) {
            return ROUTE_GRAND_TO_SM;
        }
        if (normalizedRoute.equals(normalizeRoute(ROUTE_SM_TO_GRAND))) {
            return ROUTE_SM_TO_GRAND;
        }

        if (latitude != null && longitude != null) {
            double distanceToSm = distanceKm(latitude, longitude, SM_TERMINAL_LAT, SM_TERMINAL_LNG);
            double distanceToGrand = distanceKm(latitude, longitude, GRAND_TERMINAL_LAT, GRAND_TERMINAL_LNG);
            return distanceToSm <= distanceToGrand ? ROUTE_GRAND_TO_SM : ROUTE_SM_TO_GRAND;
        }

        return ROUTE_SM_TO_GRAND;
    }

    private double distanceToDestinationKm(Double latitude, Double longitude, String routeDirection) {
        if (latitude == null || longitude == null) {
            return 0.0;
        }

        if (ROUTE_GRAND_TO_SM.equals(routeDirection)) {
            return distanceKm(latitude, longitude, SM_TERMINAL_LAT, SM_TERMINAL_LNG);
        }
        return distanceKm(latitude, longitude, GRAND_TERMINAL_LAT, GRAND_TERMINAL_LNG);
    }

    private String normalizeRoute(String route) {
        return String.valueOf(route)
                .toLowerCase(Locale.ROOT)
                .replace("\u2192", "to")
                .replace("->", "to")
                .replace("-", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        final double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
