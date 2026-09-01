package com.premier.admin.controller;

import com.premier.device.model.Device;
import com.premier.device.repository.DeviceRepository;
import com.premier.driver.model.DriverLocation;
import com.premier.driver.model.Vehicle;
import com.premier.driver.repository.DriverLocationRepository;
import com.premier.driver.repository.VehicleRepository;
import com.premier.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only monitoring for the fixed Point A to Point B service. */
@RestController
@RequestMapping("/api/admin/vehicle-monitoring")
@RequiredArgsConstructor
public class AdminVehicleMonitoringController {
    private static final String FIXED_ROUTE = "SM Lipa → Grand Terminal";

    private final VehicleRepository vehicleRepository;
    private final DriverLocationRepository locationRepository;
    private final DeviceRepository deviceRepository;

    @Value("${vehicle.monitoring.online-minutes:5}")
    private long onlineMinutes;
    @Value("${vehicle.monitoring.offline-minutes:15}")
    private long offlineMinutes;
    @Value("${premier.terminals.sm.latitude:13.954781}")
    private double pointALatitude;
    @Value("${premier.terminals.sm.longitude:121.163096}")
    private double pointALongitude;
    @Value("${premier.terminals.grand.latitude:13.790391}")
    private double pointBLatitude;
    @Value("${premier.terminals.grand.longitude:121.062721}")
    private double pointBLongitude;

    @GetMapping("/buses")
    public ApiResponse<Map<String, Object>> buses() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, DriverLocation> latestByPlate = locationRepository.findLatestValidPerPlate().stream()
                .collect(Collectors.toMap(location -> normalizePlate(location.getPlateNumber()),
                        Function.identity(), this::newerLocation));
        Map<String, LocalDateTime> lastSeenByPlate = deviceRepository.findAll().stream()
                .filter(device -> device.getPlateNumber() != null && device.getLastSeenAt() != null)
                .collect(Collectors.toMap(device -> normalizePlate(device.getPlateNumber()), Device::getLastSeenAt,
                        (first, second) -> first.isAfter(second) ? first : second));
        List<Map<String, Object>> buses = vehicleRepository.findAll().stream()
                .sorted(Comparator.comparing(Vehicle::getPlateNumber, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(vehicle -> busResponse(vehicle, latestByPlate.get(normalizePlate(vehicle.getPlateNumber())),
                        lastSeenByPlate.get(normalizePlate(vehicle.getPlateNumber())), now))
                .toList();
        return ApiResponse.success("Vehicle monitoring data fetched.", mapOf(
                "route", fixedRoute(),
                "statusThresholds", mapOf("onlineMinutes", onlineMinutes, "offlineMinutes", offlineMinutes),
                "buses", buses));
    }

    @GetMapping("/location-history/{plateNumber}")
    public ApiResponse<Map<String, Object>> locationHistory(
            @PathVariable String plateNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime,
            @RequestParam(required = false) String range) {
        String normalizedPlate = normalizePlate(plateNumber);
        if (vehicleRepository.findByPlateNumber(normalizedPlate).isEmpty()) {
            throw new IllegalArgumentException("Vehicle not found.");
        }
        DateWindow window = historyWindow(date, startTime, endTime, range);
        List<DriverLocation> locations = locationRepository
                .findByPlateNumberAndRecordedAtBetweenOrderByRecordedAtAsc(normalizedPlate, window.start(), window.end())
                .stream().filter(this::validCoordinates).toList();
        return ApiResponse.success("Vehicle location history fetched.", mapOf(
                "plateNumber", normalizedPlate,
                "route", fixedRoute(),
                "filters", mapOf("date", window.start().toLocalDate(), "startTime", window.start().toLocalTime(),
                        "endTime", window.end().toLocalTime()),
                "summary", historySummary(locations),
                "history", locations.stream().map(this::historyPoint).toList()));
    }

    private Map<String, Object> busResponse(Vehicle vehicle, DriverLocation location,
                                            LocalDateTime deviceLastSeen, LocalDateTime now) {
        String gpsStatus = monitoringStatus(location, now);
        LocalDateTime gpsUpdatedAt = location == null ? null : location.getRecordedAt();
        return mapOf("vehicleId", vehicle.getId(), "plateNumber", vehicle.getPlateNumber(),
                "route", FIXED_ROUTE, "configuredRoute", vehicle.getRoute(), "vehicleStatus", vehicle.getStatus().name(),
                "status", gpsStatus, "totalCapacity", vehicle.getTotalCapacity(), "hasValidLocation", location != null,
                "latitude", location == null ? null : location.getLatitude(),
                "longitude", location == null ? null : location.getLongitude(),
                "speed", location == null ? 0.0 : safeSpeed(location.getSpeed()), "gpsStatus", movementStatus(location),
                "lastUpdated", gpsUpdatedAt, "lastSeen", newest(deviceLastSeen, gpsUpdatedAt),
                "online", "ONLINE".equals(gpsStatus), "locationFresh", "ONLINE".equals(gpsStatus));
    }

    private Map<String, Object> historyPoint(DriverLocation location) {
        return mapOf("id", location.getId(), "plateNumber", location.getPlateNumber(),
                "latitude", location.getLatitude(), "longitude", location.getLongitude(),
                "speed", safeSpeed(location.getSpeed()), "heading", location.getHeading(),
                "status", movementStatus(location), "recordedAt", location.getRecordedAt());
    }

    private Map<String, Object> historySummary(List<DriverLocation> locations) {
        if (locations.isEmpty()) {
            return mapOf("gpsRecords", 0, "firstRecorded", null, "lastRecorded", null,
                    "totalRecordedSeconds", 0, "totalDistanceKm", null, "averageSpeedKmh", null,
                    "maximumSpeedKmh", null, "numberOfStops", null, "longestStopSeconds", null);
        }
        double distance = 0, speedTotal = 0, maxSpeed = 0;
        int stops = 0;
        long longestStopSeconds = 0;
        LocalDateTime stopStartedAt = null;
        for (int index = 0; index < locations.size(); index++) {
            DriverLocation point = locations.get(index);
            if (index > 0) distance += distanceKm(locations.get(index - 1), point);
            double speed = safeSpeed(point.getSpeed());
            speedTotal += speed;
            maxSpeed = Math.max(maxSpeed, speed);
            if (speed < 1.0 && stopStartedAt == null) {
                stopStartedAt = point.getRecordedAt();
                stops++;
            } else if (speed >= 1.0 && stopStartedAt != null) {
                longestStopSeconds = Math.max(longestStopSeconds,
                        Duration.between(stopStartedAt, point.getRecordedAt()).getSeconds());
                stopStartedAt = null;
            }
        }
        if (stopStartedAt != null) {
            longestStopSeconds = Math.max(longestStopSeconds,
                    Duration.between(stopStartedAt, locations.get(locations.size() - 1).getRecordedAt()).getSeconds());
        }
        LocalDateTime first = locations.get(0).getRecordedAt();
        LocalDateTime last = locations.get(locations.size() - 1).getRecordedAt();
        return mapOf("gpsRecords", locations.size(), "firstRecorded", first, "lastRecorded", last,
                "totalRecordedSeconds", Math.max(0, Duration.between(first, last).getSeconds()),
                "totalDistanceKm", decimal(distance), "averageSpeedKmh", decimal(speedTotal / locations.size()),
                "maximumSpeedKmh", decimal(maxSpeed), "numberOfStops", stops,
                "longestStopSeconds", longestStopSeconds);
    }

    private Map<String, Object> fixedRoute() {
        return mapOf("name", FIXED_ROUTE,
                "pointA", mapOf("code", "POINT_A", "label", "SM Lipa", "description", "Starting Terminal",
                        "latitude", pointALatitude, "longitude", pointALongitude),
                "pointB", mapOf("code", "POINT_B", "label", "Grand Terminal", "description", "Destination Terminal",
                        "latitude", pointBLatitude, "longitude", pointBLongitude));
    }

    private DateWindow historyWindow(LocalDate date, LocalTime startTime, LocalTime endTime, String range) {
        LocalDateTime now = LocalDateTime.now();
        if (date == null && range != null) {
            return switch (range.trim().toLowerCase()) {
                case "hour" -> new DateWindow(now.minusHours(1), now);
                case "week" -> new DateWindow(now.minusDays(7), now);
                default -> new DateWindow(now.toLocalDate().atStartOfDay(), now);
            };
        }
        LocalDate selectedDate = date == null ? now.toLocalDate() : date;
        if (selectedDate.isAfter(now.toLocalDate())) throw new IllegalArgumentException("History date cannot be in the future.");
        LocalTime from = startTime == null ? LocalTime.MIN : startTime;
        LocalTime to = endTime == null ? LocalTime.MAX : endTime;
        if (from.isAfter(to)) throw new IllegalArgumentException("Start time cannot be after end time.");
        return new DateWindow(selectedDate.atTime(from), selectedDate.atTime(to));
    }

    private boolean validCoordinates(DriverLocation location) {
        if (location == null || location.getLatitude() == null || location.getLongitude() == null) return false;
        double latitude = location.getLatitude(), longitude = location.getLongitude();
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180
                && latitude != 0.0 && longitude != 0.0;
    }

    private String monitoringStatus(DriverLocation location, LocalDateTime now) {
        if (location == null || location.getRecordedAt() == null) return "OFFLINE";
        long ageMinutes = Math.max(0, Duration.between(location.getRecordedAt(), now).toMinutes());
        if (ageMinutes < onlineMinutes) return "ONLINE";
        if (ageMinutes < offlineMinutes) return "DELAYED";
        return "OFFLINE";
    }

    private String movementStatus(DriverLocation location) {
        return location == null ? "No GPS" : safeSpeed(location.getSpeed()) >= 1.0 ? "Moving" : "Stopped";
    }

    private double safeSpeed(Double speed) {
        return speed == null || !Double.isFinite(speed) || speed < 0 ? 0.0 : speed;
    }

    private DriverLocation newerLocation(DriverLocation first, DriverLocation second) {
        if (first.getRecordedAt() == null) return second;
        if (second.getRecordedAt() == null) return first;
        return first.getRecordedAt().isAfter(second.getRecordedAt()) ? first : second;
    }

    private LocalDateTime newest(LocalDateTime first, LocalDateTime second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }

    private double distanceKm(DriverLocation first, DriverLocation second) {
        final double radiusKm = 6371.0;
        double latDistance = Math.toRadians(second.getLatitude() - first.getLatitude());
        double lonDistance = Math.toRadians(second.getLongitude() - first.getLongitude());
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(first.getLatitude())) * Math.cos(Math.toRadians(second.getLatitude()))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        return radiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizePlate(String plateNumber) {
        return plateNumber == null ? "" : plateNumber.trim().toUpperCase();
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) map.put(String.valueOf(values[index]), values[index + 1]);
        return map;
    }

    private record DateWindow(LocalDateTime start, LocalDateTime end) {}
}
