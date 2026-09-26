package com.premier.device.service;
import com.premier.device.model.*;
import com.premier.device.repository.*;
import com.premier.device.request.GpsTelemetryRequest;
import com.premier.device.security.DevicePrincipal;
import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import com.premier.realtime.RealtimeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
@Service @RequiredArgsConstructor @Slf4j
public class GpsTelemetryService {
    private final DeviceService authentication;
    private final DeviceRepository devices;
    private final DriverLocationRepository locations;
    private final GpsObservationRepository observations;
    private final RealtimeEventPublisher events;
    private final com.premier.staffqueue.service.BusQueueService busQueueService;
    @Transactional
    public Map<String, Object> receive(DevicePrincipal principal, GpsTelemetryRequest fix) {
        authentication.lockPaymentDevice(principal);
        authentication.requirePlateAssignment(principal, fix.getPlateNumber());
        Vehicle vehicle = authentication.requireAssignedVehicle(principal);
        authentication.validateFreshNonce(principal, fix.getRequestNonce(), fix.getRequestTimestamp());
        Device device = devices.findById(principal.id()).orElseThrow();
        Instant now = Instant.now(); String status = "GPS_VALID", reason = "ACCEPTED";
        if (!Boolean.TRUE.equals(fix.getFixValid())) { status = "GPS_NO_FIX"; reason = "NO_FIX"; }
        else if (!coordinates(fix.getLatitude(), fix.getLongitude()) || fix.getCapturedAt() == null
                || fix.getCapturedAt().isAfter(now.plusSeconds(10)) || !bounded(fix.getSpeed(), 0, 180)
                || !bounded(fix.getHeading(), 0, 360)) { status = "GPS_INVALID"; reason = "INVALID_FIELDS"; }
        else if (fix.getCapturedAt().isBefore(now.minusSeconds(45))) { status = "GPS_STALE"; reason = "STALE_CAPTURE"; }
        else if (fix.getSatellites() == null || fix.getSatellites() < 4 || fix.getSatellites() > 100
                || fix.getHdop() == null || !Double.isFinite(fix.getHdop()) || fix.getHdop() <= 0 || fix.getHdop() > 5) {
            status = "GPS_INVALID"; reason = "LOW_QUALITY";
        }
        String plate = vehicle.getPlateNumber().trim().toUpperCase(Locale.ROOT);
        var previous = locations.findTopByPlateNumberOrderByRecordedAtDesc(plate);
        if ("GPS_VALID".equals(status) && previous.isPresent() && previous.get().getCapturedAt() != null) {
            var prior = previous.get(); long elapsed = Duration.between(prior.getCapturedAt(), fix.getCapturedAt()).toMillis();
            if (elapsed <= 0) { status = "GPS_INVALID"; reason = "OUT_OF_ORDER"; }
            else if (distanceKm(prior.getLatitude(), prior.getLongitude(), fix.getLatitude(), fix.getLongitude())
                    > 0.2 + elapsed * 0.00005) { status = "GPS_INVALID"; reason = "IMPOSSIBLE_JUMP"; }
        }
        GpsObservation observation = new GpsObservation(); observation.setDeviceId(principal.deviceId());
        observation.setPlateNumber(plate); observation.setShiftId(null);
        observation.setCapturedAt(fix.getCapturedAt()); observation.setReceivedAt(now); observation.setStatus(status); observation.setReason(reason);
        observation.setLatitude(finiteOrNull(fix.getLatitude())); observation.setLongitude(finiteOrNull(fix.getLongitude()));
        observation.setSatellites(fix.getSatellites()); observation.setHdop(finiteOrNull(fix.getHdop())); observation.setFixType(fix.getFixType());
        observations.save(observation); device.setGpsState(status);
        if ("GPS_VALID".equals(status)) {
            LocalDateTime captured = LocalDateTime.ofInstant(fix.getCapturedAt(), ZoneId.of("Asia/Manila"));
            device.setGpsCapturedAt(fix.getCapturedAt());
            locations.save(DriverLocation.builder().plateNumber(plate).shiftId(null)
                    .latitude(fix.getLatitude()).longitude(fix.getLongitude()).recordedAt(captured)
                    .capturedAt(fix.getCapturedAt()).receivedAt(now).deviceId(principal.deviceId())
                    .satellites(fix.getSatellites()).hdop(fix.getHdop()).fixType(fix.getFixType())
                    .speed(fix.getSpeed()).heading(fix.getHeading()).build());
        }
        devices.save(device);
        if ("GPS_VALID".equals(status)) {
            try {
                busQueueService.checkInFromGps(vehicle.getId(), fix.getLatitude(), fix.getLongitude());
            } catch (RuntimeException queueFailure) {
                // Queue persistence must not make otherwise-valid GPS ingestion unavailable.
                log.warn("Terminal queue GPS check-in failed vehicle={} type={}",
                        vehicle.getId(), queueFailure.getClass().getSimpleName());
            }
        }
        events.admin("VEHICLE_LOCATION_UPDATED", "VEHICLE_LOCATION", observation.getId());
        events.staff("VEHICLE_LOCATION_UPDATED", "VEHICLE_LOCATION", observation.getId());
        return Map.of("status", status, "reason", reason, "receivedAt", now);
    }
    public static boolean coordinates(Double lat, Double lon) {
        return lat != null && lon != null && Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }
    private static Double finiteOrNull(Double value) { return value != null && Double.isFinite(value) ? value : null; }
    private static boolean bounded(Double value, double low, double high) { return value == null || Double.isFinite(value) && value >= low && value <= high; }
    public static double distanceKm(double a, double b, double c, double d) {
        double x = Math.pow(Math.sin(Math.toRadians(c-a)/2), 2) + Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.pow(Math.sin(Math.toRadians(d-b)/2),2);
        return 6371 * 2 * Math.asin(Math.sqrt(Math.min(1, x)));
    }
}

