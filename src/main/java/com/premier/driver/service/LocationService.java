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
import java.util.stream.Collectors;  
import java.time.LocalDateTime;
import java.util.*;  
import java.util.stream.Collectors;  

@Service
@RequiredArgsConstructor
@Slf4j
public class LocationService {

    private final DriverLocationRepository locationRepo;
    private final VehicleRepository        vehicleRepo;
    private final DriverShiftRepository    shiftRepo;
    private final SimpMessagingTemplate    ws;

    // Route endpoints 
    private static final double SM_LIPA_LAT     = 13.9411;
    private static final double SM_LIPA_LNG     = 121.1638;
    private static final double SM_BATANGAS_LAT = 13.7565;
    private static final double SM_BATANGAS_LNG = 121.0584;
    private static final double GEOFENCE_RADIUS = 0.3; // km

    //POST 
    @Transactional
    public ApiResponse<Map<String, Object>> updateLocation(LocationRequest req) {
        String plate = req.getPlateNumber().toUpperCase();

        log.info("📍 GPS update: {} @ ({},{}) speed={}kmh", 
            plate, req.getLatitude(), req.getLongitude(), req.getSpeed());

        try {
            // Update vehicle live coords
            Vehicle vehicle = vehicleRepo.findByPlateNumber(plate)
                .orElseThrow(() -> new RuntimeException("Vehicle not found: " + plate));

            vehicle.setLatitude(req.getLatitude());
            vehicle.setLongitude(req.getLongitude());
            vehicleRepo.save(vehicle);

            // Resolve shift
            Long shiftId = req.getShiftId();
            if (shiftId == null) {
                shiftId = shiftRepo.findByVehiclePlateNumberAndStatus(plate, ShiftStatus.ACTIVE)
                    .map(DriverShift::getId).orElse(null);
            }

            // Save GPS history
            DriverLocation loc = DriverLocation.builder()
                .plateNumber(plate)
                .shiftId(shiftId)
                .latitude(req.getLatitude())
                .longitude(req.getLongitude())
                .speed(req.getSpeed() != null ? req.getSpeed() : 0.0)
                .heading(req.getHeading() != null ? req.getHeading() : 0.0)
                .recordedAt(LocalDateTime.now()) 
                .build();
            locationRepo.save(loc);

            // Route intelligence
            String geofence = checkGeofence(req.getLatitude(), req.getLongitude());
            boolean deviated = isDeviated(req.getLatitude(), req.getLongitude());
            Map<String, Object> etaData = computeEta(req.getLatitude(), req.getLongitude(), req.getSpeed());

            // WebSocket broadcast
            Map<String, Object> wsPayload = new LinkedHashMap<>();
            wsPayload.put("plateNumber", plate);
            wsPayload.put("latitude", req.getLatitude());
            wsPayload.put("longitude", req.getLongitude());
            wsPayload.put("speed", req.getSpeed());
            wsPayload.put("heading", req.getHeading());
            wsPayload.put("geofence", geofence);
            wsPayload.put("deviated", deviated);
            wsPayload.put("eta", etaData);
            wsPayload.put("timestamp", LocalDateTime.now().toString());
            
            ws.convertAndSend("/topic/bus-locations", (Object) wsPayload);

            //  HTTP response
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "OK");
            result.put("savedId", loc.getId());
            result.put("geofence", geofence);
            result.put("deviated", deviated);
            result.put("eta", etaData);

            log.info("GPS saved: {} (geofence={})", plate, geofence);
            return ApiResponse.success("Location updated.", result);

        } catch (Exception e) {
            log.error("GPS failed for {}: {}", plate, e.getMessage(), e);
            return ApiResponse.error("GPS update failed: " + e.getMessage());
        }
    }

    // GET 
    public ApiResponse<List<Map<String, Object>>> getLiveLocations() {
        List<DriverLocation> latest = locationRepo.findLatestPerPlate();
        
        List<Map<String, Object>> locations = latest.stream()
            .map(loc -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("plateNumber", loc.getPlateNumber());
                m.put("latitude", loc.getLatitude());
                m.put("longitude", loc.getLongitude());
                m.put("speed", loc.getSpeed());
                m.put("heading", loc.getHeading());
                m.put("recordedAt", loc.getRecordedAt().toString());
                m.put("route", getRouteName(loc.getPlateNumber()));  
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

    //  Route Intelligence 
    private String checkGeofence(double lat, double lng) {
        if (distKm(lat, lng, SM_LIPA_LAT, SM_LIPA_LNG) <= GEOFENCE_RADIUS) 
            return "AT_SM_LIPA";
        if (distKm(lat, lng, SM_BATANGAS_LAT, SM_BATANGAS_LNG) <= GEOFENCE_RADIUS) 
            return "AT_SM_BATANGAS";
        return "EN_ROUTE";
    }

    private boolean isDeviated(double lat, double lng) {
        return lat < 13.74 || lat > 13.96 || lng < 121.04 || lng > 121.18;
    }

    private Map<String, Object> computeEta(double lat, double lng, Double speedKmh) {
        double speed = (speedKmh != null && speedKmh > 0) ? speedKmh : 30.0;
        double dLipa = distKm(lat, lng, SM_LIPA_LAT, SM_LIPA_LNG);
        double dBatangas = distKm(lat, lng, SM_BATANGAS_LAT, SM_BATANGAS_LNG);

        Map<String, Object> eta = new LinkedHashMap<>();
        eta.put("distToSmLipaKm", round1(dLipa));
        eta.put("distToSmBatangasKm", round1(dBatangas));
        eta.put("etaToSmLipaMin", Math.round((dLipa / speed) * 60));
        eta.put("etaToSmBatangasMin", Math.round((dBatangas / speed) * 60));
        return eta;
    }

    private double distKm(double la1, double lo1, double la2, double lo2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(la2 - la1);
        double dLon = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private String getRouteName(String plateNumber) {
        return "SM Lipa ↔ SM Batangas";
    }

    //  Cleanup (2AM daily) 
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupOldLocations() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        long count = locationRepo.countByRecordedAtBefore(cutoff);  
        locationRepo.deleteByRecordedAtBefore(cutoff);
        log.info("🧹 GPS cleanup: deleted {} records before {}", count, cutoff);
    }
}
