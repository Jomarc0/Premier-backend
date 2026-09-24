package com.premier.trip.service;

import com.premier.driver.model.DriverShift;
import com.premier.driver.model.ShiftStatus;
import com.premier.driver.repository.DriverShiftRepository;
import com.premier.driver.security.DriverPrincipal;
import com.premier.exception.ClientException;
import com.premier.response.ApiResponse;
import com.premier.trip.model.*;
import com.premier.trip.repository.VehicleTripRepository;
import com.premier.trip.response.VehicleTripResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class VehicleTripService {
    private final VehicleTripRepository trips;
    private final DriverShiftRepository shifts;

    @Value("${premier.terminals.sm.latitude:13.954781}") private double smLatitude;
    @Value("${premier.terminals.sm.longitude:121.163096}") private double smLongitude;
    @Value("${premier.terminals.grand.latitude:13.790391}") private double grandLatitude;
    @Value("${premier.terminals.grand.longitude:121.062721}") private double grandLongitude;
    @Value("${premier.trips.terminal-radius-km:0.5}") private double terminalRadiusKm;
    @Value("${premier.trips.location-max-age-seconds:120}") private long locationMaxAgeSeconds;

    @Transactional
    public ApiResponse<VehicleTripResponse> start(DriverPrincipal principal, TripDirection direction) {
        DriverShift shift = lockedActiveShift(principal);
        trips.findByVehicleIdAndStatus(shift.getVehicle().getId(), TripStatus.ACTIVE).ifPresent(existing -> {
            throw conflict("Complete the active trip before starting another one.");
        });
        requireAtTerminal(shift, direction.origin());
        LocalDateTime now = LocalDateTime.now();
        VehicleTrip trip = trips.save(VehicleTrip.builder().vehicle(shift.getVehicle())
                .vehiclePlateNumber(shift.getVehicle().getPlateNumber()).driverShift(shift)
                .direction(direction).originTerminal(direction.origin()).destinationTerminal(direction.destination())
                .startedAt(now).status(TripStatus.ACTIVE).build());
        shift.getVehicle().setRoute(direction.routeLabel());
        return ApiResponse.success("Trip started.", VehicleTripResponse.from(trip));
    }

    @Transactional
    public ApiResponse<VehicleTripResponse> complete(DriverPrincipal principal, Long tripId) {
        DriverShift shift = lockedActiveShift(principal);
        VehicleTrip trip = trips.findById(tripId)
                .filter(row -> row.getStatus() == TripStatus.ACTIVE
                        && row.getDriverShift().getId().equals(shift.getId())
                        && row.getVehicle().getId().equals(shift.getVehicle().getId()))
                .orElseThrow(() -> new ClientException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "Active trip not found."));
        requireAtTerminal(shift, trip.getDestinationTerminal());
        trip.setStatus(TripStatus.COMPLETED);
        trip.setEndedAt(LocalDateTime.now());
        return ApiResponse.success("Trip completed.", VehicleTripResponse.from(trip));
    }

    @Transactional(readOnly = true)
    public Optional<VehicleTrip> activeForVehicle(Long vehicleId) {
        return vehicleId == null ? Optional.empty() : trips.findByVehicleIdAndStatus(vehicleId, TripStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public Optional<VehicleTrip> findForFare(Long vehicleId, LocalDateTime capturedAt) {
        if (vehicleId == null) return Optional.empty();
        LocalDateTime moment = capturedAt == null ? LocalDateTime.now() : capturedAt;
        return trips.findCovering(vehicleId, moment, PageRequest.of(0, 1)).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public VehicleTrip requireForFare(Long vehicleId, LocalDateTime capturedAt) {
        if (vehicleId == null) throw reconciliation("Fare has no authenticated vehicle association.");
        return findForFare(vehicleId, capturedAt)
                .orElseThrow(() -> reconciliation("Vehicle has no trip covering this fare's capture time."));
    }

    @Transactional
    public void cancelActiveForShift(DriverShift shift) {
        if (shift == null || shift.getVehicle() == null) return;
        trips.findByVehicleIdAndStatus(shift.getVehicle().getId(), TripStatus.ACTIVE).ifPresent(trip -> {
            trip.setStatus(TripStatus.CANCELLED);
            trip.setEndedAt(LocalDateTime.now());
        });
    }

    private DriverShift lockedActiveShift(DriverPrincipal principal) {
        if (principal == null) throw new ClientException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Driver authentication required.");
        return shifts.findLockedById(principal.shiftId())
                .filter(shift -> shift.getStatus() == ShiftStatus.ACTIVE
                        && shift.getDriver().getId().equals(principal.driverId())
                        && shift.getVehicle().getId().equals(principal.vehicleId()))
                .orElseThrow(() -> new ClientException(HttpStatus.UNAUTHORIZED, "SHIFT_INACTIVE", "Driver shift is no longer active."));
    }

    private void requireAtTerminal(DriverShift shift, String terminal) {
        LocalDateTime now = LocalDateTime.now();
        if (shift.getCurrentLatitude() == null || shift.getCurrentLongitude() == null
                || shift.getLastLocationUpdate() == null
                || shift.getLastLocationUpdate().isBefore(now.minusSeconds(locationMaxAgeSeconds))) {
            throw conflict("A recent GPS position is required before changing trip state.");
        }
        boolean sm = "SM Terminal".equals(terminal);
        double distance = distanceKm(shift.getCurrentLatitude(), shift.getCurrentLongitude(),
                sm ? smLatitude : grandLatitude, sm ? smLongitude : grandLongitude);
        if (distance > terminalRadiusKm) throw conflict("Vehicle must be at " + terminal + " to change this trip state.");
    }

    private double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private ClientException conflict(String message) {
        return new ClientException(HttpStatus.CONFLICT, "TRIP_STATE_CONFLICT", message);
    }

    private ClientException reconciliation(String message) {
        return new ClientException(HttpStatus.CONFLICT, "RECONCILIATION_REQUIRED", message);
    }
}
