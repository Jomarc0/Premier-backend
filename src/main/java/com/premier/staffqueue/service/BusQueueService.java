package com.premier.staffqueue.service;

import com.premier.driver.model.DriverLocation;
import com.premier.driver.model.Vehicle;
import com.premier.driver.repository.DriverLocationRepository;
import com.premier.driver.repository.VehicleRepository;
import com.premier.staffqueue.model.BusQueueStatus;
import com.premier.staffqueue.response.BusQueueDashboardResponse;
import com.premier.staffqueue.response.BusQueueItemResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class BusQueueService {

    private static final String SM_TO_GRAND = "SM Terminal to Grand Terminal";
    private static final String GRAND_TO_SM = "Grand Terminal to SM Terminal";
    private static final double DEFAULT_SPEED_KMH = 30.0;

    private final VehicleRepository vehicleRepository;
    private final DriverLocationRepository locationRepository;

    @Value("${premier.terminals.sm.latitude:13.954781}")
    private double smTerminalLatitude;

    @Value("${premier.terminals.sm.longitude:121.163096}")
    private double smTerminalLongitude;

    @Value("${premier.terminals.grand.latitude:13.790391}")
    private double grandTerminalLatitude;

    @Value("${premier.terminals.grand.longitude:121.062721}")
    private double grandTerminalLongitude;

    public BusQueueDashboardResponse getDashboard() {
        List<QueueCandidate> incomingToSm = buildQueue(GRAND_TO_SM);
        List<QueueCandidate> incomingToGrand = buildQueue(SM_TO_GRAND);

        return new BusQueueDashboardResponse(
                LocalDateTime.now(),
                numberQueue(incomingToSm),
                numberQueue(incomingToGrand)
        );
    }

    private List<QueueCandidate> buildQueue(String routeDirection) {
        return vehicleRepository.findAll().stream()
                .filter(vehicle -> routeMatches(vehicle.getRoute(), routeDirection))
                .map(vehicle -> toCandidate(vehicle, routeDirection))
                .sorted(Comparator
                        .comparing(QueueCandidate::distanceForSort)
                        .thenComparing(QueueCandidate::etaForSort)
                        .thenComparing(candidate -> candidate.plateNumber))
                .toList();
    }

    private QueueCandidate toCandidate(Vehicle vehicle, String routeDirection) {
        String plateNumber = vehicle.getPlateNumber();

        if (vehicle.getLatitude() == null || vehicle.getLongitude() == null) {
            return new QueueCandidate(
                    plateNumber,
                    routeDirection,
                    null,
                    null,
                    BusQueueStatus.DEPARTED
            );
        }

        boolean incomingToSm = GRAND_TO_SM.equals(routeDirection);
        double destinationLat = incomingToSm ? smTerminalLatitude : grandTerminalLatitude;
        double destinationLng = incomingToSm ? smTerminalLongitude : grandTerminalLongitude;
        double originLat = incomingToSm ? grandTerminalLatitude : smTerminalLatitude;
        double originLng = incomingToSm ? grandTerminalLongitude : smTerminalLongitude;

        double distanceKm = round1(distanceKm(
                vehicle.getLatitude(),
                vehicle.getLongitude(),
                destinationLat,
                destinationLng
        ));
        double originDistanceKm = round1(distanceKm(
                vehicle.getLatitude(),
                vehicle.getLongitude(),
                originLat,
                originLng
        ));
        double speedKmh = latestSpeedKmh(plateNumber).orElse(DEFAULT_SPEED_KMH);
        long etaMinutes = Math.max(1L, Math.round((distanceKm / speedKmh) * 60.0));

        return new QueueCandidate(
                plateNumber,
                routeDirection,
                distanceKm,
                etaMinutes,
                statusFor(distanceKm, originDistanceKm)
        );
    }

    private Optional<Double> latestSpeedKmh(String plateNumber) {
        return locationRepository.findTopByPlateNumberOrderByRecordedAtDesc(plateNumber)
                .map(DriverLocation::getSpeed)
                .filter(speed -> speed != null && speed > 0);
    }

    private List<BusQueueItemResponse> numberQueue(List<QueueCandidate> candidates) {
        AtomicInteger position = new AtomicInteger(1);
        return candidates.stream()
                .map(candidate -> new BusQueueItemResponse(
                        candidate.plateNumber(),
                        candidate.routeDirection(),
                        candidate.distanceKm(),
                        candidate.etaMinutes(),
                        position.getAndIncrement(),
                        candidate.status(),
                        candidate.status().getLabel()
                ))
                .toList();
    }

    private boolean routeMatches(String route, String requiredRoute) {
        if (route == null || route.isBlank()) {
            return false;
        }

        String normalizedRoute = normalize(route);
        return normalizedRoute.equals(normalize(requiredRoute));
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace("\u2192", "to")
                .replace("->", "to")
                .replace("-", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private BusQueueStatus statusFor(double destinationDistanceKm, double originDistanceKm) {
        if (destinationDistanceKm <= 0.05) {
            return BusQueueStatus.ARRIVED;
        }
        if (destinationDistanceKm <= 0.3) {
            return BusQueueStatus.ARRIVING;
        }
        if (destinationDistanceKm <= 1.0) {
            return BusQueueStatus.NEAR_TERMINAL;
        }
        if (originDistanceKm <= 0.3) {
            return BusQueueStatus.AT_TERMINAL;
        }
        return BusQueueStatus.ON_ROUTE;
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

    private record QueueCandidate(
            String plateNumber,
            String routeDirection,
            Double distanceKm,
            Long etaMinutes,
            BusQueueStatus status
    ) {
        double distanceForSort() {
            return distanceKm == null ? Double.MAX_VALUE : distanceKm;
        }

        long etaForSort() {
            return etaMinutes == null ? Long.MAX_VALUE : etaMinutes;
        }
    }
}
