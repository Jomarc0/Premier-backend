package com.premier.admin.service;

import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import com.premier.model.*;
import com.premier.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminAnalyticsService {

    private final PassengerRepository passengerRepository;
    private final TransactionRepository transactionRepository;
    private final TopUpRequestRepository topUpRequestRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final DriverShiftRepository driverShiftRepository;
    private final PassengerOnboardRepository passengerOnboardRepository;
    private final EmergencyAlertRepository emergencyAlertRepository;
    private final DriverLocationRepository driverLocationRepository;

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy");

    @Transactional(readOnly = true)
    public Map<String, Object> getAnalytics(String range, LocalDate from, LocalDate to,
                                            String route, String bus) {
        DateWindow window = resolveWindow(range, from, to);
        List<Passenger> passengers = passengerRepository.findAll();
        List<Transaction> transactions = transactionRepository.findAll();
        List<TopUpRequest> topUps = topUpRequestRepository.findAll();
        List<Vehicle> vehicles = vehicleRepository.findAll();
        List<Driver> drivers = driverRepository.findAll();
        List<DriverShift> shifts = driverShiftRepository.findAll();
        List<PassengerOnboard> onboards = passengerOnboardRepository.findAll();
        List<EmergencyAlert> emergencies = emergencyAlertRepository.findAll();
        List<DriverLocation> locations = driverLocationRepository.findAll();

        List<Transaction> txInRange = transactions.stream()
                .filter(tx -> inWindow(tx.getCreatedAt(), window))
                .toList();
        List<TopUpRequest> topUpsInRange = topUps.stream()
                .filter(t -> inWindow(t.getCreatedAt(), window))
                .toList();
        List<DriverShift> shiftsInRange = shifts.stream()
                .filter(s -> inWindow(s.getShiftStart(), window))
                .filter(s -> routeMatches(s.getVehicle(), route))
                .filter(s -> busMatches(s.getVehicle(), bus))
                .toList();
        List<PassengerOnboard> onboardsInRange = onboards.stream()
                .filter(o -> inWindow(o.getBoardedAt(), window))
                .filter(o -> routeMatches(o.getShift().getVehicle(), route))
                .filter(o -> busMatches(o.getShift().getVehicle(), bus))
                .toList();
        List<EmergencyAlert> emergenciesInRange = emergencies.stream()
                .filter(e -> inWindow(e.getCreatedAt(), window))
                .filter(e -> routeMatches(e.getVehicle(), route))
                .filter(e -> busMatches(e.getVehicle(), bus))
                .toList();
        List<DriverLocation> locationsInRange = locations.stream()
                .filter(l -> inWindow(l.getRecordedAt(), window))
                .filter(l -> bus == null || bus.isBlank() || l.getPlateNumber().equalsIgnoreCase(bus))
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("filters", filters(window, range, route, bus));
        data.put("executive", executive(passengers, txInRange, transactions, vehicles, shiftsInRange,
                emergenciesInRange, onboardsInRange));
        data.put("passengerAnalytics", passengerAnalytics(passengers, txInRange, onboardsInRange, window));
        data.put("rfidAnalytics", rfidAnalytics(passengers, txInRange));
        data.put("revenueAnalytics", revenueAnalytics(txInRange, onboardsInRange, window));
        data.put("topUpAnalytics", topUpAnalytics(topUpsInRange, txInRange, window));
        data.put("busAnalytics", busAnalytics(vehicles, shiftsInRange, onboardsInRange, txInRange));
        data.put("gpsAnalytics", gpsAnalytics(locationsInRange, vehicles));
        data.put("queueTerminalAnalytics", queueTerminalAnalytics(vehicles, onboardsInRange));
        data.put("routeAnalytics", routeAnalytics(vehicles, shiftsInRange, onboardsInRange, txInRange));
        data.put("driverConductorAnalytics", driverAnalytics(drivers, shiftsInRange, onboardsInRange, txInRange));
        data.put("emergencyAnalytics", emergencyAnalytics(emergenciesInRange, window));
        data.put("operationalAnalytics", operationalAnalytics(vehicles, shiftsInRange, onboardsInRange, txInRange));
        data.put("predictiveAnalytics", predictiveAnalytics(transactions, onboards, vehicles));
        data.put("dataSources", dataSources());
        return data;
    }

    private Map<String, Object> executive(List<Passenger> passengers, List<Transaction> txInRange,
                                          List<Transaction> allTransactions, List<Vehicle> vehicles,
                                          List<DriverShift> shiftsInRange,
                                          List<EmergencyAlert> emergenciesInRange,
                                          List<PassengerOnboard> onboardsInRange) {
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(WeekFields.ISO.dayOfWeek(), 1);
        LocalDate monthStart = today.withDayOfMonth(1);

        BigDecimal revenueToday = fareRevenue(allTransactions.stream()
                .filter(t -> sameDay(t.getCreatedAt(), today)).toList());
        BigDecimal revenueWeek = fareRevenue(allTransactions.stream()
                .filter(t -> dateBetween(t.getCreatedAt(), weekStart, today)).toList());
        BigDecimal revenueMonth = fareRevenue(allTransactions.stream()
                .filter(t -> dateBetween(t.getCreatedAt(), monthStart, today)).toList());

        return mapOf(
                "totalRegisteredPassengers", passengers.size(),
                "activePassengersToday", distinctFarePassengers(allTransactions.stream()
                        .filter(t -> sameDay(t.getCreatedAt(), today)).toList()),
                "totalRevenueToday", revenueToday,
                "revenueThisWeek", revenueWeek,
                "revenueThisMonth", revenueMonth,
                "activeBuses", vehicles.stream().filter(v -> v.getStatus() == VehicleStatus.ACTIVE).count(),
                "busesOnRoute", vehicles.stream().filter(v -> v.getStatus() == VehicleStatus.ACTIVE
                        && !atTerminal(v)).count(),
                "busesAtTerminal", vehicles.stream().filter(this::atTerminal).count(),
                "totalTripsToday", shiftsInRange.stream().filter(s -> sameDay(s.getShiftStart(), today)).count(),
                "emergencyAlertsToday", emergenciesInRange.stream().filter(e -> sameDay(e.getCreatedAt(), today)).count(),
                "averageWaitingTimeMinutes", null,
                "averageArrivalTimeMinutes", avgTripMinutes(onboardsInRange)
        );
    }

    private Map<String, Object> passengerAnalytics(List<Passenger> passengers, List<Transaction> txInRange,
                                                   List<PassengerOnboard> onboardsInRange, DateWindow window) {
        Map<Long, Long> tripCounts = fareTransactions(txInRange).stream()
                .collect(Collectors.groupingBy(t -> t.getPassenger().getId(), Collectors.counting()));

        List<Map<String, Object>> frequent = tripCounts.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> mapOf("passengerId", e.getKey(), "trips", e.getValue()))
                .toList();

        return mapOf(
                "summary", mapOf(
                        "totalRegisteredPassengers", passengers.size(),
                        "activePassengers", passengers.stream().filter(p -> p.getStatus() == PassengerStatus.ACTIVE).count(),
                        "inactivePassengers", passengers.stream().filter(p -> p.getStatus() != PassengerStatus.ACTIVE).count(),
                        "newRegistrations", passengers.stream().filter(p -> inWindow(p.getCreatedAt(), window)).count(),
                        "averageTripsPerPassenger", passengers.isEmpty() ? 0 : round((double) fareTransactions(txInRange).size() / passengers.size())
                ),
                "dailyPassengerCount", countByDay(passengers.stream().map(Passenger::getCreatedAt).toList()),
                "passengerGrowthTrend", cumulativePassengers(passengers),
                "passengerActivityTrend", countTransactionsByBucket(fareTransactions(txInRange), window),
                "frequentTravelers", frequent,
                "mostActivePassengers", frequent,
                "peakTravelHours", peakHours(fareTransactions(txInRange)),
                "routeUsageDistribution", countOnboardsByRoute(onboardsInRange)
        );
    }

    private Map<String, Object> rfidAnalytics(List<Passenger> passengers, List<Transaction> txInRange) {
        List<Transaction> fareTx = fareTransactions(txInRange);
        long successful = fareTx.stream().filter(this::successful).count();
        long failed = fareTx.stream().filter(t -> !successful(t)).count();
        return mapOf(
                "summary", mapOf(
                        "totalRfidCardsIssued", passengers.stream().filter(p -> p.getRfidUid() != null && !p.getRfidUid().isBlank()).count(),
                        "activeRfidCards", passengers.stream().filter(p -> p.getRfidUid() != null && p.getStatus() == PassengerStatus.ACTIVE).count(),
                        "blockedRfidCards", passengers.stream().filter(p -> p.getRfidUid() != null && p.getStatus() == PassengerStatus.BLOCKED).count(),
                        "lostRfidCards", null,
                        "lowBalanceAccounts", passengers.stream().filter(p -> p.getBalance().compareTo(new BigDecimal("100.00")) < 0).count(),
                        "successfulRfidTransactions", successful,
                        "failedRfidTransactions", failed,
                        "invalidTapAttempts", null,
                        "totalRfidTaps", fareTx.size()
                ),
                "rfidUsageTrend", countTransactionsByBucket(fareTx, null),
                "rfidSuccessRate", fareTx.isEmpty() ? 0 : round((successful * 100.0) / fareTx.size()),
                "rfidActivityDistribution", peakHours(fareTx)
        );
    }

    private Map<String, Object> revenueAnalytics(List<Transaction> txInRange, List<PassengerOnboard> onboardsInRange,
                                                 DateWindow window) {
        List<Transaction> fareTx = fareTransactions(txInRange).stream().filter(this::successful).toList();
        Map<String, BigDecimal> revenuePerRoute = onboardsInRange.stream()
                .collect(Collectors.groupingBy(o -> safeRoute(o.getShift().getVehicle()),
                        Collectors.reducing(BigDecimal.ZERO, PassengerOnboard::getFare, BigDecimal::add)));
        Map<String, BigDecimal> revenuePerBus = onboardsInRange.stream()
                .collect(Collectors.groupingBy(o -> o.getShift().getVehicle().getPlateNumber(),
                        Collectors.reducing(BigDecimal.ZERO, PassengerOnboard::getFare, BigDecimal::add)));

        return mapOf(
                "summary", mapOf(
                        "revenueToday", sumAmount(fareTx.stream().filter(t -> sameDay(t.getCreatedAt(), LocalDate.now())).toList()),
                        "revenueThisWeek", sumAmount(fareTx.stream().filter(t -> dateBetween(t.getCreatedAt(),
                                LocalDate.now().with(WeekFields.ISO.dayOfWeek(), 1), LocalDate.now())).toList()),
                        "revenueThisMonth", sumAmount(fareTx.stream().filter(t -> dateBetween(t.getCreatedAt(),
                                LocalDate.now().withDayOfMonth(1), LocalDate.now())).toList()),
                        "revenueThisYear", sumAmount(fareTx.stream().filter(t -> t.getCreatedAt() != null
                                && t.getCreatedAt().getYear() == LocalDate.now().getYear()).toList()),
                        "averageFareCollected", averageAmount(fareTx),
                        "highestRevenueRoute", topBigDecimal(revenuePerRoute),
                        "highestRevenueBus", topBigDecimal(revenuePerBus)
                ),
                "revenueTrend", sumTransactionsByBucket(fareTx, window),
                "revenuePerRoute", toChart(revenuePerRoute, "route", "revenue"),
                "revenuePerBus", toChart(revenuePerBus, "plateNumber", "revenue"),
                "monthlyRevenueComparison", sumTransactionsByMonth(fareTx)
        );
    }

    private Map<String, Object> topUpAnalytics(List<TopUpRequest> topUpsInRange, List<Transaction> txInRange,
                                               DateWindow window) {
        List<Transaction> topupTx = txInRange.stream().filter(t -> t.getType() == TransactionType.TOPUP).toList();
        long successful = topUpsInRange.stream().filter(t -> t.getStatus() == TransactionStatus.SUCCESS
                || t.getStatus() == TransactionStatus.COMPLETED).count();
        long failed = topUpsInRange.stream().filter(t -> t.getStatus() == TransactionStatus.FAILED
                || t.getStatus() == TransactionStatus.CANCELLED || t.getStatus() == TransactionStatus.EXPIRED).count();
        BigDecimal total = topUpsInRange.stream()
                .filter(t -> t.getStatus() == TransactionStatus.SUCCESS || t.getStatus() == TransactionStatus.COMPLETED)
                .map(TopUpRequest::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return mapOf(
                "summary", mapOf(
                        "totalTopUps", topUpsInRange.size(),
                        "successfulTopUps", successful,
                        "failedPayments", failed,
                        "averageTopUpAmount", successful == 0 ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(successful), 2, RoundingMode.HALF_UP),
                        "totalTopUpRevenue", total,
                        "gcashTransactions", null,
                        "mayaTransactions", null,
                        "mostUsedPaymentMethod", null
                ),
                "topUpTrend", sumTransactionsByBucket(topupTx, window),
                "paymentMethodDistribution", List.of(),
                "monthlyTopUpVolume", countTopUpsByMonth(topUpsInRange)
        );
    }

    private Map<String, Object> busAnalytics(List<Vehicle> vehicles, List<DriverShift> shiftsInRange,
                                             List<PassengerOnboard> onboardsInRange, List<Transaction> txInRange) {
        Map<String, Long> tripsPerBus = shiftsInRange.stream()
                .collect(Collectors.groupingBy(s -> s.getVehicle().getPlateNumber(), Collectors.counting()));
        Map<String, Integer> passengersPerBus = onboardsInRange.stream()
                .collect(Collectors.groupingBy(o -> o.getShift().getVehicle().getPlateNumber(),
                        Collectors.summingInt(PassengerOnboard::getPassengerCount)));
        Map<String, BigDecimal> revenuePerBus = onboardsInRange.stream()
                .collect(Collectors.groupingBy(o -> o.getShift().getVehicle().getPlateNumber(),
                        Collectors.reducing(BigDecimal.ZERO, PassengerOnboard::getFare, BigDecimal::add)));
        return mapOf(
                "summary", mapOf(
                        "totalBuses", vehicles.size(),
                        "activeBuses", vehicles.stream().filter(v -> v.getStatus() == VehicleStatus.ACTIVE).count(),
                        "busesOnRoute", vehicles.stream().filter(v -> v.getStatus() == VehicleStatus.ACTIVE && !atTerminal(v)).count(),
                        "busesAtTerminal", vehicles.stream().filter(this::atTerminal).count(),
                        "mostUtilizedBus", topInteger(passengersPerBus),
                        "leastUtilizedBus", bottomInteger(passengersPerBus),
                        "highestRevenueBus", topBigDecimal(revenuePerBus),
                        "highestPassengerVolumeBus", topInteger(passengersPerBus)
                ),
                "tripsPerBus", toChartLong(tripsPerBus, "plateNumber", "trips"),
                "passengerDistributionPerBus", toChartInteger(passengersPerBus, "plateNumber", "passengers"),
                "revenuePerBus", toChart(revenuePerBus, "plateNumber", "revenue")
        );
    }

    private Map<String, Object> gpsAnalytics(List<DriverLocation> locationsInRange, List<Vehicle> vehicles) {
        Map<String, Double> distancePerBus = locationsInRange.stream()
                .collect(Collectors.groupingBy(DriverLocation::getPlateNumber,
                        Collectors.collectingAndThen(Collectors.toList(), this::distanceForLocations)));
        Map<String, Double> distancePerRoute = distancePerBus.entrySet().stream()
                .collect(Collectors.toMap(e -> routeForPlate(vehicles, e.getKey()), Map.Entry::getValue, Double::sum));
        double total = distancePerBus.values().stream().mapToDouble(Double::doubleValue).sum();
        return mapOf(
                "summary", mapOf(
                        "totalDistanceTraveled", round(total),
                        "averageTravelTime", null,
                        "fastestTrip", null,
                        "slowestTrip", null,
                        "routeCoverageStatistics", null
                ),
                "distancePerBus", toChartDouble(distancePerBus, "plateNumber", "distanceKm"),
                "distancePerRoute", toChartDouble(distancePerRoute, "route", "distanceKm"),
                "distanceTraveledTrend", distanceTrend(locationsInRange),
                "travelTimeAnalysis", List.of()
        );
    }

    private Map<String, Object> queueTerminalAnalytics(List<Vehicle> vehicles, List<PassengerOnboard> onboardsInRange) {
        long activeQueue = vehicles.stream().filter(v -> v.getStatus() == VehicleStatus.ACTIVE).count();
        return mapOf(
                "summary", mapOf(
                        "currentQueueLength", activeQueue,
                        "averageQueueLength", null,
                        "peakQueueHours", null,
                        "averageWaitingTime", null,
                        "averageArrivalTime", avgTripMinutes(onboardsInRange),
                        "averageDepartureTime", null,
                        "onTimeArrivals", null,
                        "delayedArrivals", null,
                        "arrivalAccuracyPercentage", null
                ),
                "queueTrend", List.of(),
                "waitingTimeTrend", List.of(),
                "arrivalPerformanceTrend", List.of()
        );
    }

    private Map<String, Object> routeAnalytics(List<Vehicle> vehicles, List<DriverShift> shiftsInRange,
                                               List<PassengerOnboard> onboardsInRange, List<Transaction> txInRange) {
        Map<String, Integer> passengerPerRoute = countOnboardsByRouteMap(onboardsInRange);
        Map<String, BigDecimal> revenuePerRoute = onboardsInRange.stream()
                .collect(Collectors.groupingBy(o -> safeRoute(o.getShift().getVehicle()),
                        Collectors.reducing(BigDecimal.ZERO, PassengerOnboard::getFare, BigDecimal::add)));
        return mapOf(
                "summary", mapOf(
                        "mostPopularRoute", topInteger(passengerPerRoute),
                        "leastPopularRoute", bottomInteger(passengerPerRoute),
                        "highestRevenueRoute", topBigDecimal(revenuePerRoute),
                        "averageTravelTimePerRoute", null,
                        "routeEfficiencyScore", null
                ),
                "routePopularity", toChartInteger(passengerPerRoute, "route", "passengers"),
                "passengerDistributionByRoute", toChartInteger(passengerPerRoute, "route", "passengers"),
                "revenueByRoute", toChart(revenuePerRoute, "route", "revenue")
        );
    }

    private Map<String, Object> driverAnalytics(List<Driver> drivers, List<DriverShift> shiftsInRange,
                                                List<PassengerOnboard> onboardsInRange, List<Transaction> txInRange) {
        Map<String, Long> trips = shiftsInRange.stream()
                .collect(Collectors.groupingBy(s -> s.getDriver().getFullName(), Collectors.counting()));
        Map<String, Integer> passengers = onboardsInRange.stream()
                .collect(Collectors.groupingBy(o -> o.getShift().getDriver().getFullName(),
                        Collectors.summingInt(PassengerOnboard::getPassengerCount)));
        Map<String, BigDecimal> revenue = onboardsInRange.stream()
                .collect(Collectors.groupingBy(o -> o.getShift().getDriver().getFullName(),
                        Collectors.reducing(BigDecimal.ZERO, PassengerOnboard::getFare, BigDecimal::add)));
        return mapOf(
                "summary", mapOf(
                        "tripsManaged", shiftsInRange.size(),
                        "passengersServed", onboardsInRange.stream().mapToInt(PassengerOnboard::getPassengerCount).sum(),
                        "revenueCollected", revenue.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add),
                        "averageTripDuration", avgShiftMinutes(shiftsInRange),
                        "onTimePerformance", null,
                        "delayPercentage", null
                ),
                "driverPerformance", toChartLong(trips, "driver", "trips"),
                "conductorPerformance", List.of(),
                "tripCompletionStatistics", toChartLong(shiftsInRange.stream()
                        .collect(Collectors.groupingBy(s -> s.getStatus().name(), Collectors.counting())), "status", "trips"),
                "passengersServedByDriver", toChartInteger(passengers, "driver", "passengers"),
                "revenueByDriver", toChart(revenue, "driver", "revenue")
        );
    }

    private Map<String, Object> emergencyAnalytics(List<EmergencyAlert> emergenciesInRange, DateWindow window) {
        Map<String, Long> perBus = emergenciesInRange.stream()
                .collect(Collectors.groupingBy(e -> e.getVehicle().getPlateNumber(), Collectors.counting()));
        Map<String, Long> perRoute = emergenciesInRange.stream()
                .collect(Collectors.groupingBy(e -> safeRoute(e.getVehicle()), Collectors.counting()));
        return mapOf(
                "summary", mapOf(
                        "totalEmergencyAlerts", emergenciesInRange.size(),
                        "activeEmergencies", emergenciesInRange.stream().filter(e -> e.getStatus() == AlertStatus.ACTIVE).count(),
                        "resolvedEmergencies", emergenciesInRange.stream().filter(e -> e.getStatus() == AlertStatus.RESOLVED).count()
                ),
                "emergencyReportsPerBus", toChartLong(perBus, "plateNumber", "alerts"),
                "emergencyReportsPerRoute", toChartLong(perRoute, "route", "alerts"),
                "monthlyEmergencyFrequency", countEmergenciesByMonth(emergenciesInRange),
                "emergencyTrend", countEmergenciesByBucket(emergenciesInRange, window)
        );
    }

    private Map<String, Object> operationalAnalytics(List<Vehicle> vehicles, List<DriverShift> shiftsInRange,
                                                     List<PassengerOnboard> onboardsInRange, List<Transaction> txInRange) {
        long completed = shiftsInRange.stream().filter(s -> s.getStatus() == ShiftStatus.COMPLETED).count();
        BigDecimal revenue = onboardsInRange.stream().map(PassengerOnboard::getFare).reduce(BigDecimal.ZERO, BigDecimal::add);
        int passengers = onboardsInRange.stream().mapToInt(PassengerOnboard::getPassengerCount).sum();
        Map<String, Double> occupancyPerBus = onboardsInRange.stream()
                .collect(Collectors.groupingBy(o -> o.getShift().getVehicle().getPlateNumber(),
                        Collectors.collectingAndThen(Collectors.toList(), list -> occupancy(list, list.get(0).getShift().getVehicle().getTotalCapacity()))));
        Map<String, Double> occupancyPerRoute = onboardsInRange.stream()
                .collect(Collectors.groupingBy(o -> safeRoute(o.getShift().getVehicle()),
                        Collectors.collectingAndThen(Collectors.toList(), list -> occupancy(list,
                                list.stream().mapToInt(o -> o.getShift().getVehicle().getTotalCapacity()).sum()))));
        return mapOf(
                "summary", mapOf(
                        "totalTrips", shiftsInRange.size(),
                        "completedTrips", completed,
                        "cancelledTrips", shiftsInRange.stream().filter(s -> s.getStatus() == ShiftStatus.CANCELLED).count(),
                        "averageRevenuePerTrip", shiftsInRange.isEmpty() ? BigDecimal.ZERO : revenue.divide(BigDecimal.valueOf(shiftsInRange.size()), 2, RoundingMode.HALF_UP),
                        "averagePassengersPerTrip", shiftsInRange.isEmpty() ? 0 : round((double) passengers / shiftsInRange.size()),
                        "averageFleetOccupancy", occupancyPerBus.values().stream().mapToDouble(Double::doubleValue).average().orElse(0)
                ),
                "occupancyPerBus", toChartDouble(occupancyPerBus, "plateNumber", "occupancyRate"),
                "occupancyPerRoute", toChartDouble(occupancyPerRoute, "route", "occupancyRate"),
                "occupancyTrend", List.of(),
                "fleetUtilizationTrend", toChartDouble(occupancyPerBus, "plateNumber", "occupancyRate")
        );
    }

    private Map<String, Object> predictiveAnalytics(List<Transaction> transactions, List<PassengerOnboard> onboards,
                                                    List<Vehicle> vehicles) {
        LocalDate today = LocalDate.now();
        List<Transaction> last7Fare = fareTransactions(transactions).stream()
                .filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().toLocalDate().isBefore(today.minusDays(7)))
                .toList();
        List<Map<String, Object>> daily = sumTransactionsByDay(last7Fare);
        double expectedPassengers = movingAverageCount(last7Fare, 7);
        double expectedRevenue = movingAverageRevenue(last7Fare, 7);
        List<Map<String, Object>> peak = peakHours(last7Fare);
        Set<String> busyRoutes = onboards.stream()
                .filter(o -> o.getBoardedAt() != null && !o.getBoardedAt().toLocalDate().isBefore(today.minusDays(7)))
                .collect(Collectors.groupingBy(o -> safeRoute(o.getShift().getVehicle()), Collectors.counting()))
                .entrySet().stream()
                .filter(e -> e.getValue() >= Math.max(1, expectedPassengers / Math.max(1, vehicles.size())))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return mapOf(
                "method", "7-day moving average from historical fare transactions and onboard records",
                "expectedPassengersTomorrow", Math.round(expectedPassengers),
                "expectedRevenueTomorrow", round(expectedRevenue),
                "expectedPeakTravelHours", peak.stream().limit(3).toList(),
                "routesRequiringAdditionalBuses", new ArrayList<>(busyRoutes),
                "passengerDemandForecast", daily,
                "revenueForecast", daily,
                "routeDemandForecast", countOnboardsByRoute(onboards),
                "peakHourForecast", peak
        );
    }

    private Map<String, Object> dataSources() {
        return mapOf(
                "passengers", "passengers table",
                "rfid", "passengers.rfid_uid and fare transactions",
                "revenue", "transactions and passenger_onboards.fare",
                "topUps", "topup_requests and TOPUP transactions",
                "buses", "vehicles, driver_shifts, passenger_onboards",
                "gps", "driver_locations and vehicle live coordinates",
                "queueTerminal", "vehicle live status and passenger_onboards timestamps; waiting-time fields are not currently stored",
                "routes", "vehicles.route through shifts/onboard records",
                "drivers", "drivers, driver_shifts, passenger_onboards",
                "emergencies", "emergency_alerts",
                "predictive", "moving average over transactions and passenger_onboards"
        );
    }

    private DateWindow resolveWindow(String range, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        LocalDate start;
        LocalDate end = to != null ? to : today;
        switch (range == null ? "month" : range.toLowerCase(Locale.ROOT)) {
            case "daily", "day" -> start = today;
            case "weekly", "week" -> start = today.with(WeekFields.ISO.dayOfWeek(), 1);
            case "yearly", "year" -> start = today.withDayOfYear(1);
            case "custom" -> start = from != null ? from : today.withDayOfMonth(1);
            default -> start = today.withDayOfMonth(1);
        }
        if (from != null && (range == null || !"custom".equalsIgnoreCase(range))) start = from;
        return new DateWindow(start.atStartOfDay(), end.plusDays(1).atStartOfDay().minusNanos(1));
    }

    private boolean inWindow(LocalDateTime value, DateWindow window) {
        return value != null && !value.isBefore(window.start()) && !value.isAfter(window.end());
    }

    private boolean sameDay(LocalDateTime value, LocalDate day) {
        return value != null && value.toLocalDate().equals(day);
    }

    private boolean dateBetween(LocalDateTime value, LocalDate start, LocalDate end) {
        return value != null && !value.toLocalDate().isBefore(start) && !value.toLocalDate().isAfter(end);
    }

    private List<Transaction> fareTransactions(List<Transaction> transactions) {
        return transactions.stream()
                .filter(t -> t.getType() == TransactionType.FARE_DEDUCTION || t.getType() == TransactionType.RIDE_FARE)
                .toList();
    }

    private boolean successful(Transaction t) {
        return t.getStatus() == TransactionStatus.SUCCESS || t.getStatus() == TransactionStatus.COMPLETED;
    }

    private BigDecimal fareRevenue(List<Transaction> transactions) {
        return sumAmount(fareTransactions(transactions).stream().filter(this::successful).toList());
    }

    private BigDecimal sumAmount(List<Transaction> transactions) {
        return transactions.stream().map(Transaction::getAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal averageAmount(List<Transaction> transactions) {
        if (transactions.isEmpty()) return BigDecimal.ZERO;
        return sumAmount(transactions).divide(BigDecimal.valueOf(transactions.size()), 2, RoundingMode.HALF_UP);
    }

    private long distinctFarePassengers(List<Transaction> transactions) {
        return fareTransactions(transactions).stream().map(t -> t.getPassenger().getId()).distinct().count();
    }

    private boolean routeMatches(Vehicle vehicle, String route) {
        return route == null || route.isBlank() || (vehicle != null && route.equalsIgnoreCase(vehicle.getRoute()));
    }

    private boolean busMatches(Vehicle vehicle, String bus) {
        return bus == null || bus.isBlank() || (vehicle != null && bus.equalsIgnoreCase(vehicle.getPlateNumber()));
    }

    private boolean atTerminal(Vehicle vehicle) {
        return vehicle.getLatitude() != null && vehicle.getLongitude() != null
                && (distance(vehicle.getLatitude(), vehicle.getLongitude(), 13.954781, 121.163096) <= 0.3
                || distance(vehicle.getLatitude(), vehicle.getLongitude(), 13.790391, 121.062721) <= 0.3);
    }

    private String safeRoute(Vehicle vehicle) {
        return vehicle == null || vehicle.getRoute() == null || vehicle.getRoute().isBlank() ? "Unassigned Route" : vehicle.getRoute();
    }

    private String routeForPlate(List<Vehicle> vehicles, String plate) {
        return vehicles.stream().filter(v -> v.getPlateNumber().equalsIgnoreCase(plate))
                .findFirst().map(this::safeRoute).orElse("Unassigned Route");
    }

    private Double avgTripMinutes(List<PassengerOnboard> onboards) {
        OptionalDouble average = onboards.stream()
                .filter(o -> o.getBoardedAt() != null && o.getDroppedOffAt() != null)
                .mapToLong(o -> ChronoUnit.MINUTES.between(o.getBoardedAt(), o.getDroppedOffAt()))
                .average();
        return average.isPresent() ? round(average.getAsDouble()) : null;
    }

    private Double avgShiftMinutes(List<DriverShift> shifts) {
        OptionalDouble average = shifts.stream()
                .filter(s -> s.getShiftStart() != null && s.getShiftEnd() != null)
                .mapToLong(s -> ChronoUnit.MINUTES.between(s.getShiftStart(), s.getShiftEnd()))
                .average();
        return average.isPresent() ? round(average.getAsDouble()) : null;
    }

    private List<Map<String, Object>> countTransactionsByBucket(List<Transaction> transactions, DateWindow window) {
        return transactions.stream()
                .filter(t -> t.getCreatedAt() != null)
                .collect(Collectors.groupingBy(t -> bucketLabel(t.getCreatedAt()), TreeMap::new, Collectors.counting()))
                .entrySet().stream().map(e -> mapOf("name", e.getKey(), "count", e.getValue())).toList();
    }

    private List<Map<String, Object>> sumTransactionsByBucket(List<Transaction> transactions, DateWindow window) {
        return transactions.stream()
                .filter(t -> t.getCreatedAt() != null)
                .collect(Collectors.groupingBy(t -> bucketLabel(t.getCreatedAt()), TreeMap::new,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)))
                .entrySet().stream().map(e -> mapOf("name", e.getKey(), "revenue", e.getValue())).toList();
    }

    private List<Map<String, Object>> sumTransactionsByDay(List<Transaction> transactions) {
        return transactions.stream()
                .filter(t -> t.getCreatedAt() != null)
                .collect(Collectors.groupingBy(t -> t.getCreatedAt().toLocalDate().format(DAY_LABEL), TreeMap::new,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)))
                .entrySet().stream().map(e -> mapOf("name", e.getKey(), "revenue", e.getValue())).toList();
    }

    private List<Map<String, Object>> sumTransactionsByMonth(List<Transaction> transactions) {
        return transactions.stream()
                .filter(t -> t.getCreatedAt() != null)
                .collect(Collectors.groupingBy(t -> t.getCreatedAt().format(MONTH_LABEL), TreeMap::new,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)))
                .entrySet().stream().map(e -> mapOf("name", e.getKey(), "revenue", e.getValue())).toList();
    }

    private List<Map<String, Object>> countTopUpsByMonth(List<TopUpRequest> topUps) {
        return topUps.stream()
                .filter(t -> t.getCreatedAt() != null)
                .collect(Collectors.groupingBy(t -> t.getCreatedAt().format(MONTH_LABEL), TreeMap::new, Collectors.counting()))
                .entrySet().stream().map(e -> mapOf("name", e.getKey(), "count", e.getValue())).toList();
    }

    private List<Map<String, Object>> countEmergenciesByBucket(List<EmergencyAlert> emergencies, DateWindow window) {
        return emergencies.stream()
                .filter(e -> e.getCreatedAt() != null)
                .collect(Collectors.groupingBy(e -> bucketLabel(e.getCreatedAt()), TreeMap::new, Collectors.counting()))
                .entrySet().stream().map(e -> mapOf("name", e.getKey(), "alerts", e.getValue())).toList();
    }

    private List<Map<String, Object>> countEmergenciesByMonth(List<EmergencyAlert> emergencies) {
        return emergencies.stream()
                .filter(e -> e.getCreatedAt() != null)
                .collect(Collectors.groupingBy(e -> e.getCreatedAt().format(MONTH_LABEL), TreeMap::new, Collectors.counting()))
                .entrySet().stream().map(e -> mapOf("name", e.getKey(), "alerts", e.getValue())).toList();
    }

    private List<Map<String, Object>> countByDay(List<LocalDateTime> values) {
        return values.stream().filter(Objects::nonNull)
                .collect(Collectors.groupingBy(v -> v.toLocalDate().format(DAY_LABEL), TreeMap::new, Collectors.counting()))
                .entrySet().stream().map(e -> mapOf("name", e.getKey(), "count", e.getValue())).toList();
    }

    private List<Map<String, Object>> cumulativePassengers(List<Passenger> passengers) {
        List<LocalDate> dates = passengers.stream().map(Passenger::getCreatedAt).filter(Objects::nonNull)
                .map(LocalDateTime::toLocalDate).sorted().toList();
        List<Map<String, Object>> result = new ArrayList<>();
        int running = 0;
        LocalDate last = null;
        for (LocalDate date : dates) {
            running++;
            if (!date.equals(last)) {
                result.add(mapOf("name", date.format(DAY_LABEL), "count", running));
                last = date;
            } else {
                result.set(result.size() - 1, mapOf("name", date.format(DAY_LABEL), "count", running));
            }
        }
        return result;
    }

    private List<Map<String, Object>> peakHours(List<Transaction> transactions) {
        return transactions.stream().filter(t -> t.getCreatedAt() != null)
                .collect(Collectors.groupingBy(t -> String.format("%02d:00", t.getCreatedAt().getHour()), TreeMap::new, Collectors.counting()))
                .entrySet().stream().map(e -> mapOf("name", e.getKey(), "count", e.getValue())).toList();
    }

    private List<Map<String, Object>> countOnboardsByRoute(List<PassengerOnboard> onboards) {
        return toChartInteger(countOnboardsByRouteMap(onboards), "route", "passengers");
    }

    private Map<String, Integer> countOnboardsByRouteMap(List<PassengerOnboard> onboards) {
        return onboards.stream()
                .collect(Collectors.groupingBy(o -> safeRoute(o.getShift().getVehicle()),
                        Collectors.summingInt(PassengerOnboard::getPassengerCount)));
    }

    private String bucketLabel(LocalDateTime time) {
        return time.toLocalDate().format(DAY_LABEL);
    }

    private double distanceForLocations(List<DriverLocation> points) {
        List<DriverLocation> ordered = points.stream()
                .filter(p -> p.getLatitude() != null && p.getLongitude() != null && p.getRecordedAt() != null)
                .sorted(Comparator.comparing(DriverLocation::getRecordedAt)).toList();
        double total = 0;
        for (int i = 1; i < ordered.size(); i++) {
            DriverLocation a = ordered.get(i - 1);
            DriverLocation b = ordered.get(i);
            total += distance(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
        }
        return round(total);
    }

    private List<Map<String, Object>> distanceTrend(List<DriverLocation> locations) {
        return locations.stream().filter(l -> l.getRecordedAt() != null)
                .collect(Collectors.groupingBy(l -> l.getRecordedAt().toLocalDate().format(DAY_LABEL), TreeMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), this::distanceForLocations)))
                .entrySet().stream().map(e -> mapOf("name", e.getKey(), "distanceKm", e.getValue())).toList();
    }

    private double occupancy(List<PassengerOnboard> records, int capacity) {
        int passengers = records.stream().mapToInt(PassengerOnboard::getPassengerCount).sum();
        return capacity <= 0 ? 0 : round((passengers * 100.0) / capacity);
    }

    private double movingAverageCount(List<Transaction> transactions, int days) {
        return round(transactions.size() / (double) Math.max(1, days));
    }

    private double movingAverageRevenue(List<Transaction> transactions, int days) {
        return round(sumAmount(transactions).doubleValue() / Math.max(1, days));
    }

    private double distance(double la1, double lo1, double la2, double lo2) {
        double dLat = Math.toRadians(la2 - la1);
        double dLon = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Object topBigDecimal(Map<String, BigDecimal> values) {
        return values.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(e -> mapOf("name", e.getKey(), "value", e.getValue())).orElse(null);
    }

    private Object topInteger(Map<String, Integer> values) {
        return values.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(e -> mapOf("name", e.getKey(), "value", e.getValue())).orElse(null);
    }

    private Object bottomInteger(Map<String, Integer> values) {
        return values.entrySet().stream().min(Map.Entry.comparingByValue())
                .map(e -> mapOf("name", e.getKey(), "value", e.getValue())).orElse(null);
    }

    private List<Map<String, Object>> toChart(Map<String, BigDecimal> values, String labelKey, String valueKey) {
        return values.entrySet().stream().map(e -> mapOf(labelKey, e.getKey(), "name", e.getKey(), valueKey, e.getValue())).toList();
    }

    private List<Map<String, Object>> toChartLong(Map<String, Long> values, String labelKey, String valueKey) {
        return values.entrySet().stream().map(e -> mapOf(labelKey, e.getKey(), "name", e.getKey(), valueKey, e.getValue())).toList();
    }

    private List<Map<String, Object>> toChartInteger(Map<String, Integer> values, String labelKey, String valueKey) {
        return values.entrySet().stream().map(e -> mapOf(labelKey, e.getKey(), "name", e.getKey(), valueKey, e.getValue())).toList();
    }

    private List<Map<String, Object>> toChartDouble(Map<String, Double> values, String labelKey, String valueKey) {
        return values.entrySet().stream().map(e -> mapOf(labelKey, e.getKey(), "name", e.getKey(), valueKey, e.getValue())).toList();
    }

    private Map<String, Object> filters(DateWindow window, String range, String route, String bus) {
        return mapOf("range", range == null ? "month" : range, "from", window.start().toLocalDate(),
                "to", window.end().toLocalDate(), "route", route, "bus", bus);
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private record DateWindow(LocalDateTime start, LocalDateTime end) {}
}
