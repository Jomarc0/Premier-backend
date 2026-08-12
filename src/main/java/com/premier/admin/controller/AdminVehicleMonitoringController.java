package com.premier.admin.controller;

import com.premier.driver.model.DriverLocation;
import com.premier.driver.model.Vehicle;
import com.premier.driver.repository.DriverLocationRepository;
import com.premier.driver.repository.VehicleRepository;
import com.premier.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only, admin-only vehicle monitoring. GPS updates remain device-only. */
@RestController
@RequestMapping("/api/admin/vehicle-monitoring")
@RequiredArgsConstructor
public class AdminVehicleMonitoringController {

    private static final long LOCATION_FRESH_MINUTES = 5;

    private final VehicleRepository vehicleRepository;
    private final DriverLocationRepository locationRepository;

    @GetMapping("/buses")
    public ApiResponse<List<Map<String, Object>>> buses() {
        LocalDateTime freshAfter = LocalDateTime.now().minusMinutes(LOCATION_FRESH_MINUTES);
        Map<String, DriverLocation> latestByPlate = locationRepository.findLatestPerPlate().stream()
                .collect(Collectors.toMap(DriverLocation::getPlateNumber, Function.identity(), (first, ignored) -> first));

        List<Map<String, Object>> buses = vehicleRepository.findAll().stream()
                .map(vehicle -> busResponse(vehicle, latestByPlate.get(vehicle.getPlateNumber()), freshAfter))
                .toList();
        return ApiResponse.success("Vehicle monitoring data fetched.", buses);
    }

    @GetMapping("/location-history/{plateNumber}")
    public ApiResponse<List<Map<String, Object>>> locationHistory(
            @PathVariable String plateNumber,
            @RequestParam(defaultValue = "day") String range) {
        String normalizedPlate = plateNumber.trim().toUpperCase();
        List<Map<String, Object>> history = locationRepository
                .findByPlateNumberAndRecordedAtGreaterThanEqualOrderByRecordedAtAsc(normalizedPlate, historyStart(range))
                .stream()
                .map(location -> {
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("id", location.getId());
                    point.put("plateNumber", location.getPlateNumber());
                    point.put("latitude", location.getLatitude());
                    point.put("longitude", location.getLongitude());
                    point.put("speed", location.getSpeed());
                    point.put("heading", location.getHeading());
                    point.put("recordedAt", location.getRecordedAt());
                    return point;
                })
                .toList();
        return ApiResponse.success("Vehicle location history fetched.", history);
    }

    private Map<String, Object> busResponse(Vehicle vehicle, DriverLocation location, LocalDateTime freshAfter) {
        boolean fresh = location != null && location.getRecordedAt() != null
                && location.getRecordedAt().isAfter(freshAfter);
        Map<String, Object> bus = new LinkedHashMap<>();
        bus.put("vehicleId", vehicle.getId());
        bus.put("plateNumber", vehicle.getPlateNumber());
        bus.put("route", vehicle.getRoute());
        bus.put("status", vehicle.getStatus().name());
        bus.put("totalCapacity", vehicle.getTotalCapacity());
        bus.put("latitude", location != null ? location.getLatitude() : null);
        bus.put("longitude", location != null ? location.getLongitude() : null);
        bus.put("speed", fresh && location.getSpeed() != null ? location.getSpeed() : 0.0);
        bus.put("lastUpdated", location != null ? location.getRecordedAt() : null);
        bus.put("online", fresh);
        bus.put("locationFresh", fresh);
        return bus;
    }

    private LocalDateTime historyStart(String range) {
        LocalDateTime now = LocalDateTime.now();
        return switch (range == null ? "day" : range.trim().toLowerCase()) {
            case "hour" -> now.minusHours(1);
            case "week" -> now.minusDays(7);
            default -> now.toLocalDate().atStartOfDay();
        };
    }
}
