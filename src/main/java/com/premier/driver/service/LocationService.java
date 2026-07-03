package com.premier.driver.service;

import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import com.premier.driver.request.LocationRequest;
import com.premier.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LocationService {

    private static final long LIVE_LOCATION_WINDOW_MINUTES = 5;

    private final DriverLocationRepository locationRepo;
    private final VehicleRepository        vehicleRepo;
    private final DriverShiftRepository    shiftRepo;
    private final SimpMessagingTemplate    ws;

    // Route endpoints
    private static final String ROUTE_SM_TO_GRAND = "SM Terminal to Grand Terminal";
    private static final String ROUTE_GRAND_TO_SM = "Grand Terminal to SM Terminal";
    private static final String GEOFENCE_SM_TERMINAL = "AT_SM_TERMINAL";
    private static final String GEOFENCE_GRAND_TERMINAL = "AT_GRAND_TERMINAL";

    private static final double SM_LIPA_LAT     = 13.954781;
    private static final double SM_LIPA_LNG     = 121.163096;
    private static final double GRAND_TERMINAL_LAT = 13.790391;
    private static final double GRAND_TERMINAL_LNG = 121.062721;
    private static final double GEOFENCE_RADIUS = 5.0; // km

    // POST /location
    @Transactional
    public ApiResponse<Map<String, Object>> updateLocation(LocationRequest req) {
        String plate = req.getPlateNumber().toUpperCase();

        log.info("GPS update: {} @ ({},{}) speed={}kmh",
                plate, req.getLatitude(), req.getLongitude(), req.getSpeed());

        try {
            // Update vehicle live coords
            Vehicle vehicle = vehicleRepo.findByPlateNumber(plate)
                    .orElseThrow(() -> new RuntimeException("Vehicle not found: " + plate));

            String geofence = checkGeofence(req.getLatitude(), req.getLongitude());
            Optional<String> updatedRoute = routeForTerminal(geofence);

            vehicle.setLatitude(req.getLatitude());
            vehicle.setLongitude(req.getLongitude());
            updatedRoute.ifPresent(vehicle::setRoute);
            vehicleRepo.save(vehicle);

            Optional<DriverShift> activeShift = shiftRepo
                    .findByVehiclePlateNumberAndStatus(plate, ShiftStatus.ACTIVE);

            if (req.getShiftId() != null
                    && activeShift.map(DriverShift::getId)
                    .filter(req.getShiftId()::equals)
                    .isEmpty()) {
                throw new RuntimeException("Shift does not belong to this vehicle.");
            }

            Long shiftId = req.getShiftId() != null
                    ? req.getShiftId()
                    : activeShift.map(DriverShift::getId).orElse(null);

            // Update driver_shifts location columns
            activeShift.ifPresent(shift -> {
                shift.setCurrentLatitude(req.getLatitude());
                shift.setCurrentLongitude(req.getLongitude());
                shift.setLastLocationUpdate(LocalDateTime.now());
                shiftRepo.save(shift);
                log.debug("driver_shifts id={} coords updated", shift.getId());
            });

            // Save GPS history
            DriverLocation loc = DriverLocation.builder()
                    .plateNumber(plate)
                    .shiftId(shiftId)
                    .latitude(req.getLatitude())
                    .longitude(req.getLongitude())
                    .speed(req.getSpeed()    != null ? req.getSpeed()    : 0.0)
                    .heading(req.getHeading() != null ? req.getHeading() : 0.0)
                    .recordedAt(LocalDateTime.now())
                    .build();
            locationRepo.save(loc);

            // Route intelligence
            boolean             deviated = isDeviated(req.getLatitude(), req.getLongitude());
            Map<String, Object> etaData  = computeEta(req.getLatitude(), req.getLongitude(), req.getSpeed());

            // WebSocket broadcast
            Map<String, Object> wsPayload = new LinkedHashMap<>();
            wsPayload.put("plateNumber", plate);
            wsPayload.put("latitude",    req.getLatitude());
            wsPayload.put("longitude",   req.getLongitude());
            wsPayload.put("speed",       req.getSpeed());
            wsPayload.put("heading",     req.getHeading());
            wsPayload.put("geofence",    geofence);
            wsPayload.put("route",       vehicle.getRoute());
            wsPayload.put("routeUpdated", updatedRoute.isPresent());
            wsPayload.put("deviated",    deviated);
            wsPayload.put("eta",         etaData);
            wsPayload.put("timestamp",   LocalDateTime.now().toString());

            ws.convertAndSend("/topic/bus-locations", wsPayload);

            // HTTP response
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status",   "OK");
            result.put("savedId",  loc.getId());
            result.put("geofence", geofence);
            result.put("route", vehicle.getRoute());
            result.put("routeUpdated", updatedRoute.isPresent());
            result.put("deviated", deviated);
            result.put("eta",      etaData);

            log.info("GPS saved: {} (geofence={}, route={}, shiftId={})",
                    plate, geofence, vehicle.getRoute(), shiftId);
            return ApiResponse.success("Location updated.", result);

        } catch (Exception e) {
            log.error("GPS failed for {}: {}", plate, e.getMessage(), e);
            return ApiResponse.error("GPS update failed: " + e.getMessage());
        }
    }

    // GET /live-locations
    public ApiResponse<List<Map<String, Object>>> getLiveLocations() {
        LocalDateTime freshAfter = LocalDateTime.now().minusMinutes(LIVE_LOCATION_WINDOW_MINUTES);
        List<DriverLocation> latest = locationRepo.findLatestPerPlate().stream()
                .filter(loc -> loc.getRecordedAt() != null
                        && loc.getRecordedAt().isAfter(freshAfter))
                .toList();

        List<Map<String, Object>> locations = latest.stream()
                .map(loc -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("plateNumber", loc.getPlateNumber());
                    m.put("latitude",    loc.getLatitude());
                    m.put("longitude",   loc.getLongitude());
                    m.put("speed",       loc.getSpeed());
                    m.put("heading",     loc.getHeading());
                    m.put("recordedAt",  loc.getRecordedAt().toString());
                    m.put("route",       getRouteName(loc.getPlateNumber()));
                    return m;
                })
                .collect(Collectors.toList());

        return ApiResponse.success("Live locations (" + locations.size() + " buses).", locations);
    }

    // GET 
    public ApiResponse<List<DriverLocation>> getShiftHistory(Long shiftId) {
        List<DriverLocation> history = locationRepo.findByShiftIdOrderByRecordedAtAsc(shiftId);
        return ApiResponse.success("Shift history (" + history.size() + " points).", history);
    }


    private String checkGeofence(double lat, double lng) {
        if (distKm(lat, lng, SM_LIPA_LAT, SM_LIPA_LNG) <= GEOFENCE_RADIUS)
            return GEOFENCE_SM_TERMINAL;
        if (distKm(lat, lng, GRAND_TERMINAL_LAT, GRAND_TERMINAL_LNG) <= GEOFENCE_RADIUS)
            return GEOFENCE_GRAND_TERMINAL;
        return "EN_ROUTE";
    }

    private Optional<String> routeForTerminal(String geofence) {
        if (GEOFENCE_SM_TERMINAL.equals(geofence)) {
            return Optional.of(ROUTE_SM_TO_GRAND);
        }
        if (GEOFENCE_GRAND_TERMINAL.equals(geofence)) {
            return Optional.of(ROUTE_GRAND_TO_SM);
        }
        return Optional.empty();
    }

    private boolean isDeviated(double lat, double lng) {
        return lat < 13.74 || lat > 13.96 || lng < 121.04 || lng > 121.18;
    }

    private Map<String, Object> computeEta(double lat, double lng, Double speedKmh) {
        double speed    = (speedKmh != null && speedKmh > 0) ? speedKmh : 30.0;
        double dLipa    = distKm(lat, lng, SM_LIPA_LAT, SM_LIPA_LNG);
        double dGrand = distKm(lat, lng, GRAND_TERMINAL_LAT, GRAND_TERMINAL_LNG);

        Map<String, Object> eta = new LinkedHashMap<>();
        eta.put("distToSmLipaKm",      round1(dLipa));
        eta.put("distToGrandTerminalKm",  round1(dGrand));
        eta.put("etaToSmLipaMin",      Math.round((dLipa     / speed) * 60));
        eta.put("etaToGrandTerminalMin",  Math.round((dGrand / speed) * 60));
        return eta;
    }

    private double distKm(double la1, double lo1, double la2, double lo2) {
        final double R    = 6371.0;
        double       dLat = Math.toRadians(la2 - la1);
        double       dLon = Math.toRadians(lo2 - lo1);
        double       a    = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                          + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2))
                          * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private String getRouteName(String plateNumber) {
        return vehicleRepo.findByPlateNumber(plateNumber)
                .map(Vehicle::getRoute)
                .orElse(ROUTE_SM_TO_GRAND);
    }

    // Cleanup 
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupOldLocations() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        long          count  = locationRepo.countByRecordedAtBefore(cutoff);
        locationRepo.deleteByRecordedAtBefore(cutoff);
        log.info("GPS cleanup: deleted {} records before {}", count, cutoff);
    }
}

