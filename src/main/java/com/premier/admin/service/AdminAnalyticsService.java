package com.premier.admin.service;

import com.premier.device.model.Device;
import com.premier.device.model.DeviceStatus;
import com.premier.device.repository.DeviceRepository;
import com.premier.driver.model.DriverLocation;
import com.premier.driver.model.DriverShift;
import com.premier.driver.model.Vehicle;
import com.premier.driver.repository.DriverLocationRepository;
import com.premier.driver.repository.DriverShiftRepository;
import com.premier.driver.repository.VehicleRepository;
import com.premier.model.Transaction;
import com.premier.model.TransactionStatus;
import com.premier.model.TransactionType;
import com.premier.payment.model.FarePaymentAttempt;
import com.premier.payment.model.FarePaymentAttemptStatus;
import com.premier.payment.repository.FarePaymentAttemptRepository;
import com.premier.repository.TransactionRepository;
import com.premier.staffcash.model.StaffCashTransaction;
import com.premier.staffcash.repository.StaffCashTransactionRepository;
import com.premier.staffqueue.response.BusQueueItemResponse;
import com.premier.staffqueue.service.BusQueueService;
import com.premier.support.model.SupportTicket;
import com.premier.support.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Analytics for Premier's one fixed route and its two valid directions. */
@Service
@RequiredArgsConstructor
public class AdminAnalyticsService {
    public static final String SM_TO_GRAND = "SM_TO_GRAND";
    public static final String GRAND_TO_SM = "GRAND_TO_SM";
    public static final String SM_TO_GRAND_LABEL = "SM Terminal \u2192 Grand Terminal";
    public static final String GRAND_TO_SM_LABEL = "Grand Terminal \u2192 SM Terminal";

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Manila");
    private static final int OFFLINE_DEVICE_MINUTES = 5;
    private static final int MISSING_GPS_MINUTES = 15;
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private final TransactionRepository transactionRepository;
    private final FarePaymentAttemptRepository farePaymentAttemptRepository;
    private final StaffCashTransactionRepository staffCashTransactionRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverShiftRepository driverShiftRepository;
    private final DriverLocationRepository driverLocationRepository;
    private final DeviceRepository deviceRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final BusQueueService busQueueService;

    @Transactional(readOnly = true)
    public Map<String, Object> getAnalytics(String range, LocalDate from, LocalDate to, String direction, String bus) {
        return getDashboard(range, from, to, bus, direction, null, null, DEFAULT_ZONE.getId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDashboard(String range, LocalDate startDate, LocalDate endDate,
                                            String busId, String direction, String paymentMethod,
                                            String transactionStatus, String timezone) {
        ZoneId zone = resolveZone(timezone);
        DateWindow window = resolveWindow(range, startDate, endDate, zone);
        AnalyticsFilter filter = new AnalyticsFilter(blankToNull(busId), normalizeDirection(direction),
                normalizePaymentMethod(paymentMethod), normalizeStatus(transactionStatus));
        List<Vehicle> vehicles = vehicleRepository.findAll();
        List<FareRecord> selected = loadFareRecords(window).stream().filter(filter::matches).toList();
        List<AttemptRecord> attempts = loadAttempts(window).stream().filter(filter::matches).toList();
        DateWindow previousWindow = previousWindow(window);
        List<FareRecord> previous = loadFareRecords(previousWindow).stream().filter(filter::matches).toList();
        List<AttemptRecord> previousAttempts = loadAttempts(previousWindow).stream().filter(filter::matches).toList();
        LocalDate today = LocalDate.now(zone);
        DateWindow todayWindow = dayWindow(today);
        List<FareRecord> todayRecords = sameWindow(window, todayWindow) ? selected
                : loadFareRecords(todayWindow).stream().filter(filter::matches).toList();
        List<DriverShift> shifts = driverShiftRepository.findByShiftStartBetween(window.start(), window.end()).stream()
                .filter(shift -> busMatches(shift.getVehicle(), filter.busId())).toList();
        List<Device> devices = deviceRepository.findAll().stream()
                .filter(device -> filter.busId() == null || filter.busId().equalsIgnoreCase(device.getPlateNumber())).toList();
        List<DriverLocation> locations = driverLocationRepository.findLatestPerPlate().stream()
                .filter(location -> filter.busId() == null || filter.busId().equalsIgnoreCase(location.getPlateNumber())).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filters", filterMap(window, range, filter, zone));
        response.put("options", options(vehicles));
        response.put("summary", summary(selected, attempts, todayRecords, vehicles, devices));
        response.put("comparison", comparison(selected, attempts, previous, previousAttempts, window));
        response.put("trends", trends(selected, window));
        response.put("busPerformance", busPerformance(selected, attempts, vehicles));
        response.put("dailyBusPerformance", dailyBusPerformance(selected));
        response.put("directionAnalytics", directionAnalytics(selected));
        response.put("tripPerformance", unavailableTripPerformance());
        response.put("passengerAnalytics", passengerAnalytics(selected));
        response.put("paymentAnalytics", paymentAnalytics(selected, window));
        response.put("transactionAnalytics", transactionAnalytics(selected, attempts));
        response.put("fleetAnalytics", fleetAnalytics(selected, attempts, vehicles, devices, locations));
        response.put("terminalAnalytics", terminalAnalytics(selected, attempts, devices, locations, filter));
        response.put("recent", recent(selected, attempts, devices, locations, zone));
        response.put("forecast", forecast());
        response.put("definitions", definitions());
        response.put("dataQuality", dataQuality(selected));
        response.put("generatedAt", OffsetDateTime.now(zone).toString());
        response.put("charts", legacyCharts(response, shifts));
        return response;
    }

    private List<FareRecord> loadFareRecords(DateWindow window) {
        List<FareRecord> records = transactionRepository
                .findByCreatedAtBetweenOrderByCreatedAtDesc(window.start(), window.end()).stream()
                .filter(this::fareTransaction).map(this::fareRecord).collect(Collectors.toCollection(ArrayList::new));
        staffCashTransactionRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(window.start(), window.end())
                .stream().map(this::cashRecord).forEach(records::add);
        return records;
    }

    private List<AttemptRecord> loadAttempts(DateWindow window) {
        return farePaymentAttemptRepository.findByCreatedAtBetween(window.start(), window.end()).stream()
                .map(this::attemptRecord).toList();
    }

    private Map<String, Object> summary(List<FareRecord> records, List<AttemptRecord> attempts,
                                        List<FareRecord> todayRecords, List<Vehicle> vehicles, List<Device> devices) {
        List<FareRecord> successful = successful(records);
        BigDecimal revenue = sum(successful);
        Map<String, BusAggregate> buses = aggregateBuses(successful);
        BusAggregate best = buses.values().stream().max(Comparator.comparing(BusAggregate::revenue)).orElse(null);
        DirectionAggregate peak = aggregateDirections(successful).values().stream()
                .max(Comparator.comparingLong(DirectionAggregate::passengers)).orElse(null);
        List<FareRecord> todaySuccess = successful(todayRecords);
        return mapOf("fareRevenue", revenue, "totalRevenue", revenue,
                "successfulTransactions", successful.size(), "totalPassengers", successful.size(),
                "passengerMetric", "SUCCESSFUL_FARE_TRANSACTIONS", "uniquePassengers", uniquePassengers(successful),
                "totalTrips", null, "tripDataAvailable", false,
                "averageFarePerPassenger", divide(revenue, successful.size()),
                "revenueToday", sum(todaySuccess), "passengersToday", todaySuccess.size(),
                "bestPerformingBus", best == null ? null : busSummary(best),
                "peakDirection", peak == null ? null : directionSummary(peak),
                "paymentSuccessRate", paymentSuccessRate(attempts, records), "activeBuses", buses.size(),
                "offlineDevices", devices.stream().filter(this::offlineDevice).count(), "registeredBuses", vehicles.size());
    }

    private Map<String, Object> comparison(List<FareRecord> records, List<AttemptRecord> attempts,
                                           List<FareRecord> previous, List<AttemptRecord> previousAttempts,
                                           DateWindow window) {
        List<FareRecord> currentSuccess = successful(records);
        List<FareRecord> previousSuccess = successful(previous);
        return mapOf("label", previousLabel(window),
                "revenue", comparisonMetric(sum(currentSuccess), sum(previousSuccess)),
                "passengers", comparisonMetric(BigDecimal.valueOf(currentSuccess.size()), BigDecimal.valueOf(previousSuccess.size())),
                "trips", mapOf("available", false, "percentage", null),
                "paymentSuccessRate", comparisonMetric(paymentSuccessRate(attempts, records), paymentSuccessRate(previousAttempts, previous)));
    }

    private Map<String, Object> trends(List<FareRecord> records, DateWindow window) {
        List<FareRecord> successful = successful(records);
        Map<LocalDate, List<FareRecord>> byDay = successful.stream().collect(Collectors.groupingBy(r -> r.createdAt().toLocalDate()));
        List<Map<String, Object>> revenue = new ArrayList<>();
        List<Map<String, Object>> passengers = new ArrayList<>();
        for (LocalDate day = window.start().toLocalDate(); !day.isAfter(window.end().toLocalDate()); day = day.plusDays(1)) {
            List<FareRecord> rows = byDay.getOrDefault(day, List.of());
            revenue.add(mapOf("date", day, "name", day.format(DAY_LABEL), "revenue", sum(rows)));
            passengers.add(mapOf("date", day, "name", day.format(DAY_LABEL), "passengers", rows.size(), "uniquePassengers", uniquePassengers(rows)));
        }
        return mapOf("revenue", revenue, "passengers", passengers, "passengerSummary", trendSummary(passengers));
    }

    private Map<String, Object> busPerformance(List<FareRecord> records, List<AttemptRecord> attempts, List<Vehicle> vehicles) {
        Map<String, long[]> attemptCounts = attempts.stream().filter(a -> a.bus() != null).collect(Collectors.toMap(
                AttemptRecord::bus, a -> new long[]{1, a.successful() ? 1 : 0},
                (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]}, LinkedHashMap::new));
        List<Map<String, Object>> rows = aggregateBuses(successful(records)).values().stream()
                .sorted(Comparator.comparing(BusAggregate::revenue).reversed()).map(bus -> {
                    long[] counts = attemptCounts.getOrDefault(bus.bus(), new long[]{0, 0});
                    return mapOf("bus", bus.bus(), "name", bus.bus(), "passengers", bus.passengers(),
                            "revenue", bus.revenue(), "uniquePassengers", bus.uniquePassengers(),
                            "paymentSuccessRate", counts[0] == 0 ? null : percent(counts[1], counts[0]),
                            "trips", null, "tripDataAvailable", false);
                }).toList();
        return mapOf("buses", rows,
                "passengersByBus", rows.stream().map(r -> mapOf("name", r.get("bus"), "passengers", r.get("passengers"))).toList(),
                "revenueByBus", rows.stream().map(r -> mapOf("name", r.get("bus"), "revenue", r.get("revenue"))).toList(),
                "availableBuses", vehicles.stream().map(Vehicle::getPlateNumber).filter(Objects::nonNull).sorted().toList());
    }

    private List<Map<String, Object>> dailyBusPerformance(List<FareRecord> records) {
        record Key(LocalDate day, String bus) {}
        Map<Key, List<FareRecord>> grouped = successful(records).stream().filter(r -> r.bus() != null)
                .collect(Collectors.groupingBy(r -> new Key(r.createdAt().toLocalDate(), r.bus()), LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream().sorted(Map.Entry.<Key, List<FareRecord>>comparingByKey(
                Comparator.comparing(Key::day).reversed().thenComparing(Key::bus))).map(entry -> {
                    List<FareRecord> rows = entry.getValue();
                    long sm = rows.stream().filter(r -> SM_TO_GRAND.equals(r.direction())).count();
                    long grand = rows.stream().filter(r -> GRAND_TO_SM.equals(r.direction())).count();
                    return mapOf("date", entry.getKey().day(), "bus", entry.getKey().bus(),
                            "smToGrandPassengers", sm, "grandToSmPassengers", grand,
                            "unassignedDirectionPassengers", rows.size() - sm - grand, "totalPassengers", rows.size(),
                            "uniquePassengers", uniquePassengers(rows), "trips", null, "revenue", sum(rows),
                            "passengersPerTrip", null, "revenuePerTrip", null, "tripDataAvailable", false);
                }).toList();
    }

    private Map<String, Object> directionAnalytics(List<FareRecord> records) {
        Map<String, DirectionAggregate> grouped = aggregateDirections(successful(records));
        long totalPassengers = grouped.values().stream().mapToLong(DirectionAggregate::passengers).sum();
        BigDecimal totalRevenue = grouped.values().stream().map(DirectionAggregate::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> rows = List.of(SM_TO_GRAND, GRAND_TO_SM).stream().map(code -> {
            DirectionAggregate d = grouped.getOrDefault(code, new DirectionAggregate(code, directionLabel(code), 0, BigDecimal.ZERO));
            return mapOf("direction", code, "name", d.label(), "label", d.label(), "passengers", d.passengers(),
                    "transactions", d.passengers(), "revenue", d.revenue(),
                    "percentage", totalPassengers == 0 ? BigDecimal.ZERO : percent(d.passengers(), totalPassengers),
                    "revenuePercentage", totalRevenue.signum() == 0 ? BigDecimal.ZERO : percentage(d.revenue(), totalRevenue),
                    "averageFare", divide(d.revenue(), d.passengers()));
        }).toList();
        return mapOf("directions", rows, "unknownDirectionTransactions", successful(records).stream().filter(r -> r.direction() == null).count());
    }

    private Map<String, Object> unavailableTripPerformance() {
        return mapOf("available", false,
                "message", "Trip analytics are unavailable because the database does not store individual terminal-to-terminal trips or link fares to a trip. Driver shifts are not counted as trips.",
                "totalTrips", null, "completedTrips", null, "cancelledTrips", null,
                "averagePassengersPerTrip", null, "averageRevenuePerTrip", null, "byBus", List.of());
    }

    private Map<String, Object> passengerAnalytics(List<FareRecord> records) {
        List<FareRecord> successful = successful(records);
        Map<Integer, Long> hours = successful.stream().collect(Collectors.groupingBy(r -> r.createdAt().getHour(), Collectors.counting()));
        List<Map<String, Object>> hourly = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) hourly.add(mapOf("hour", hour, "name", hourLabel(hour),
                "count", hours.getOrDefault(hour, 0L), "passengers", hours.getOrDefault(hour, 0L)));
        Map.Entry<Integer, Long> peakHour = hours.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        Map<DayOfWeek, Long> days = successful.stream().collect(Collectors.groupingBy(r -> r.createdAt().getDayOfWeek(), Collectors.counting()));
        Map.Entry<DayOfWeek, Long> peakDay = days.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        DirectionAggregate peakDirection = aggregateDirections(successful).values().stream().max(Comparator.comparingLong(DirectionAggregate::passengers)).orElse(null);
        return mapOf("hourlyVolume", hourly,
                "peakHour", peakHour == null ? null : mapOf("hour", peakHour.getKey(), "label", hourRangeLabel(peakHour.getKey()), "passengers", peakHour.getValue()),
                "peakDay", peakDay == null ? null : mapOf("day", title(peakDay.getKey().name()), "passengers", peakDay.getValue()),
                "peakDirection", peakDirection == null ? null : directionSummary(peakDirection),
                "highestPassengerVolume", peakHour == null ? 0 : peakHour.getValue());
    }

    private Map<String, Object> paymentAnalytics(List<FareRecord> records, DateWindow window) {
        List<FareRecord> successful = successful(records);
        long total = successful.size();
        BigDecimal totalRevenue = sum(successful);
        List<Map<String, Object>> methods = List.of("RFID", "NFC", "QR", "ASSISTED_CASH").stream().map(method -> {
            List<FareRecord> matches = successful.stream().filter(r -> method.equals(r.paymentMethod())).toList();
            BigDecimal revenue = sum(matches);
            return mapOf("method", method, "name", paymentLabel(method), "count", matches.size(), "revenue", revenue,
                    "percentage", total == 0 ? BigDecimal.ZERO : percent(matches.size(), total),
                    "revenuePercentage", totalRevenue.signum() == 0 ? BigDecimal.ZERO : percentage(revenue, totalRevenue));
        }).toList();
        Map<LocalDate, Map<String, Long>> daily = successful.stream().collect(Collectors.groupingBy(r -> r.createdAt().toLocalDate(),
                Collectors.groupingBy(FareRecord::paymentMethod, Collectors.counting())));
        List<Map<String, Object>> trend = new ArrayList<>();
        for (LocalDate day = window.start().toLocalDate(); !day.isAfter(window.end().toLocalDate()); day = day.plusDays(1)) {
            Map<String, Long> counts = daily.getOrDefault(day, Map.of());
            trend.add(mapOf("date", day, "name", day.format(DAY_LABEL), "rfid", counts.getOrDefault("RFID", 0L),
                    "nfc", counts.getOrDefault("NFC", 0L), "qr", counts.getOrDefault("QR", 0L),
                    "assistedCash", counts.getOrDefault("ASSISTED_CASH", 0L)));
        }
        return mapOf("methods", methods, "dailyTrend", trend);
    }

    private Map<String, Object> transactionAnalytics(List<FareRecord> records, List<AttemptRecord> attempts) {
        long success = attempts.isEmpty() ? successful(records).size() : attempts.stream().filter(AttemptRecord::successful).count();
        long failed = attempts.stream().filter(a -> !a.successful()).count();
        long pending = records.stream().filter(r -> "PENDING".equals(r.status()) || "PROCESSING".equals(r.status())).count();
        long cancelled = records.stream().filter(r -> "CANCELLED".equals(r.status())).count();
        long total = success + failed + pending + cancelled;
        List<Map<String, Object>> statuses = List.of(statusRow("SUCCESSFUL", success, total), statusRow("FAILED", failed, total),
                statusRow("PENDING", pending, total), statusRow("CANCELLED", cancelled, total));
        Map<String, List<AttemptRecord>> grouped = attempts.stream().filter(a -> !a.successful()).collect(Collectors.groupingBy(
                a -> a.failureReason() == null ? "UNKNOWN" : a.failureReason(), LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> reasons = grouped.entrySet().stream().sorted(Map.Entry.<String, List<AttemptRecord>>comparingByValue(
                        Comparator.comparingInt(List::size)).reversed())
                .map(e -> mapOf("reason", e.getKey(), "label", failureLabel(e.getKey()), "count", e.getValue().size(),
                        "percentage", failed == 0 ? BigDecimal.ZERO : percent(e.getValue().size(), failed))).toList();
        List<Map<String, Object>> failures = attempts.stream().filter(a -> !a.successful())
                .sorted(Comparator.comparing(AttemptRecord::createdAt).reversed()).limit(100)
                .map(a -> mapOf("transactionId", a.transactionId(), "dateTime", a.createdAt(), "bus", a.bus(),
                        "direction", a.direction(), "directionLabel", directionLabel(a.direction()), "terminal", a.deviceId(),
                        "paymentMethod", paymentLabel(a.paymentMethod()), "failureReason", failureLabel(a.failureReason()),
                        "failureMessage", a.failureMessage())).toList();
        return mapOf("statuses", statuses, "paymentSuccessRate", total == 0 ? BigDecimal.ZERO : percent(success, total),
                "failureReasons", reasons, "failedTransactions", failures);
    }

    private Map<String, Object> fleetAnalytics(List<FareRecord> records, List<AttemptRecord> attempts, List<Vehicle> vehicles,
                                                List<Device> devices, List<DriverLocation> locations) {
        List<Map<String, Object>> rankings = aggregateBuses(successful(records)).values().stream()
                .sorted(Comparator.comparing(BusAggregate::revenue).reversed())
                .map(b -> mapOf("bus", b.bus(), "revenue", b.revenue(), "passengers", b.passengers(),
                        "uniquePassengers", b.uniquePassengers(), "trips", null, "passengersPerTrip", null, "revenuePerTrip", null)).toList();
        List<Map<String, Object>> utilization = vehicles.stream().map(v -> mapOf("bus", v.getPlateNumber(),
                "capacity", v.getTotalCapacity() > 0 ? v.getTotalCapacity() : null, "averagePassengers", null,
                "utilizationPercentage", null, "status", "Unavailable", "available", false,
                "reason", "Average passenger load requires individual trip records.")).toList();
        return mapOf("utilization", utilization, "topPerformingBuses", rankings,
                "busesRequiringAttention", busesRequiringAttention(records, attempts, devices, locations),
                "rankingMetricsAvailable", List.of("REVENUE", "PASSENGERS"),
                "rankingMetricsUnavailable", List.of("TRIPS", "PASSENGERS_PER_TRIP", "REVENUE_PER_TRIP", "UTILIZATION"));
    }

    private Map<String, Object> terminalAnalytics(List<FareRecord> records, List<AttemptRecord> attempts,
                                                   List<Device> devices, List<DriverLocation> locations, AnalyticsFilter filter) {
        long online = devices.stream().filter(d -> !offlineDevice(d)).count();
        long offline = devices.stream().filter(this::offlineDevice).count();
        Map<String, Long> processed = successful(records).stream().filter(r -> r.deviceId() != null).collect(Collectors.groupingBy(FareRecord::deviceId, Collectors.counting()));
        Map<String, Long> failed = attempts.stream().filter(a -> !a.successful() && a.deviceId() != null).collect(Collectors.groupingBy(AttemptRecord::deviceId, Collectors.counting()));
        List<Map<String, Object>> terminals = devices.stream().map(d -> mapOf("terminalId", d.getDeviceId(), "name", d.getDeviceName(),
                "bus", d.getPlateNumber(), "status", offlineDevice(d) ? "OFFLINE" : "ONLINE", "lastHeartbeat", d.getLastSeenAt(),
                "transactionsProcessed", processed.getOrDefault(d.getDeviceId(), 0L), "failedTransactions", failed.getOrDefault(d.getDeviceId(), 0L))).toList();
        return mapOf("online", online, "offline", offline, "missingGpsUpdates", locations.stream().filter(this::missingGps).count(),
                "availability", devices.isEmpty() ? null : percent(online, devices.size()), "terminals", terminals,
                "queues", terminalQueues(filter), "queueHistoryAvailable", false,
                "queueHistoryMessage", "Maximum queue, average queue, and processing time are unavailable because queue snapshots and service timestamps are not stored.");
    }

    private List<Map<String, Object>> terminalQueues(AnalyticsFilter filter) {
        try {
            var dashboard = busQueueService.getDashboard();
            List<BusQueueItemResponse> queues = new ArrayList<>();
            queues.addAll(dashboard.incomingToSmTerminal());
            queues.addAll(dashboard.incomingToGrandTerminal());
            return queues.stream().filter(q -> filter == null || filter.busId() == null || filter.busId().equalsIgnoreCase(q.plateNumber()))
                    .filter(q -> filter == null || filter.direction() == null || filter.direction().equals(normalizeDirection(q.routeDirection())))
                    .map(q -> mapOf("terminal", destinationLabel(normalizeDirection(q.routeDirection())), "bus", q.plateNumber(), "name", q.plateNumber(),
                            "direction", normalizeDirection(q.routeDirection()), "currentQueue", q.queuePosition(), "queueCount", q.queuePosition(),
                            "etaMinutes", q.etaMinutes(), "status", q.statusLabel(), "maximumQueue", null, "averageQueue", null,
                            "averageProcessingSeconds", null)).toList();
        } catch (RuntimeException ignored) { return List.of(); }
    }

    private Map<String, Object> recent(List<FareRecord> records, List<AttemptRecord> attempts, List<Device> devices,
                                       List<DriverLocation> locations, ZoneId zone) {
        List<Map<String, Object>> fares = records.stream().sorted(Comparator.comparing(FareRecord::createdAt).reversed()).limit(10)
                .map(r -> mapOf("transactionId", r.reference(), "time", formatDateTime(r.createdAt(), zone), "bus", r.bus(),
                        "direction", r.direction(), "directionLabel", directionLabel(r.direction()), "paymentMethod", paymentLabel(r.paymentMethod()),
                        "amount", r.amount(), "status", title(r.status()), "maskedCardNumber", r.passengerId() == null ? "" : "Account fare")).toList();
        List<Map<String, Object>> tickets = supportTicketRepository.findAll().stream()
                .sorted(Comparator.comparing(SupportTicket::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))).limit(10)
                .map(t -> mapOf("ticketNumber", t.getTicketNumber(), "category", title(t.getIssueType() == null ? null : t.getIssueType().name()),
                        "status", title(t.getStatus() == null ? null : t.getStatus().name()), "dateSubmitted", formatDateTime(t.getCreatedAt(), zone))).toList();
        return mapOf("fareTransactions", fares, "supportTickets", tickets, "systemAlerts", systemAlerts(attempts, devices, locations, zone));
    }

    private List<Map<String, Object>> systemAlerts(List<AttemptRecord> attempts, List<Device> devices,
                                                    List<DriverLocation> locations, ZoneId zone) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        devices.stream().filter(this::offlineDevice).limit(5).forEach(d -> alerts.add(mapOf("severity", "Critical", "title", "Vehicle terminal offline",
                "message", d.getDeviceName() + " on " + valueOr(d.getPlateNumber(), "an unassigned bus") + " has no recent heartbeat.",
                "time", formatDateTime(d.getLastSeenAt(), zone))));
        locations.stream().filter(this::missingGps).limit(5).forEach(l -> alerts.add(mapOf("severity", "Warning", "title", "Missing GPS update",
                "message", l.getPlateNumber() + " has no GPS update within " + MISSING_GPS_MINUTES + " minutes.",
                "time", formatDateTime(l.getRecordedAt(), zone))));
        attempts.stream().filter(a -> !a.successful()).collect(Collectors.groupingBy(AttemptRecord::bus)).forEach((bus, rows) -> {
            if (bus != null && rows.size() >= 5) alerts.add(mapOf("severity", "Warning", "title", "High transaction failure rate",
                    "message", bus + " recorded " + rows.size() + " failed fare attempts.", "time", ""));
        });
        return alerts.stream().limit(10).toList();
    }

    private Map<String, Object> forecast() {
        long days = transactionRepository.countDistinctFareOperatingDays();
        return mapOf("available", false, "requiredOperatingDays", 30, "currentOperatingDays", days,
                "message", days < 30 ? "Forecasting will become available after at least 30 days of valid fare and trip data."
                        : "Fare history is sufficient, but forecasting remains unavailable until valid trip history is recorded.");
    }

    private List<Map<String, Object>> busesRequiringAttention(List<FareRecord> records, List<AttemptRecord> attempts,
                                                               List<Device> devices, List<DriverLocation> locations) {
        Map<String, LinkedHashSet<String>> reasons = new LinkedHashMap<>();
        devices.stream().filter(this::offlineDevice).filter(d -> d.getPlateNumber() != null)
                .forEach(d -> reasons.computeIfAbsent(d.getPlateNumber(), k -> new LinkedHashSet<>()).add("Vehicle terminal offline"));
        locations.stream().filter(this::missingGps).forEach(l -> reasons.computeIfAbsent(l.getPlateNumber(), k -> new LinkedHashSet<>()).add("Missing GPS update"));
        attempts.stream().filter(a -> !a.successful() && a.bus() != null).collect(Collectors.groupingBy(AttemptRecord::bus)).forEach((bus, rows) -> {
            if (rows.size() >= 5) reasons.computeIfAbsent(bus, k -> new LinkedHashSet<>()).add("High transaction failure rate");
        });
        Map<String, BusAggregate> buses = aggregateBuses(successful(records));
        double average = buses.values().stream().mapToLong(BusAggregate::passengers).average().orElse(0);
        buses.values().stream().filter(b -> buses.size() > 1 && b.passengers() < average * .5)
                .forEach(b -> reasons.computeIfAbsent(b.bus(), k -> new LinkedHashSet<>()).add("Low passenger volume"));
        return reasons.entrySet().stream().map(e -> mapOf("bus", e.getKey(), "reasons", new ArrayList<>(e.getValue()),
                "reason", String.join(", ", e.getValue()))).toList();
    }

    private Map<String, Object> dataQuality(List<FareRecord> records) {
        return mapOf("tripDataAvailable", false,
                "directionAssignedTransactions", records.stream().filter(r -> r.direction() != null).count(),
                "directionMissingTransactions", records.stream().filter(r -> r.direction() == null).count(),
                "busAssignedTransactions", records.stream().filter(r -> r.bus() != null).count(),
                "busMissingTransactions", records.stream().filter(r -> r.bus() == null).count(),
                "limitations", List.of("Individual trips are not stored; trip totals and utilization are unavailable.",
                        "Assisted cash is included in passenger transactions but excluded from unique account passengers.",
                        "Historical queue and processing-time snapshots are not stored."));
    }

    private Map<String, Object> definitions() {
        return mapOf("totalRevenue", "Sum of successful account fare deductions and assisted cash fares.",
                "totalPassengers", "Successful fare transactions; this is a boarding/fare-event metric, not distinct people.",
                "uniquePassengers", "Distinct passenger accounts among successful RFID, NFC, and QR fares; assisted cash is excluded.",
                "totalTrips", "Unavailable until terminal-to-terminal trip records are stored.",
                "direction", "Only SM_TO_GRAND and GRAND_TO_SM are recognized.",
                "paymentSuccessRate", "Successful fare attempts divided by all recorded fare attempts.");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> legacyCharts(Map<String, Object> response, List<DriverShift> ignoredShifts) {
        Map<String, Object> trends = (Map<String, Object>) response.get("trends");
        Map<String, Object> payments = (Map<String, Object>) response.get("paymentAnalytics");
        Map<String, Object> passengers = (Map<String, Object>) response.get("passengerAnalytics");
        Map<String, Object> buses = (Map<String, Object>) response.get("busPerformance");
        Map<String, Object> terminals = (Map<String, Object>) response.get("terminalAnalytics");
        return mapOf("revenueTrend", trends.get("revenue"), "passengerActivityTrend", trends.get("passengers"),
                "transactionsByPaymentMethod", payments.get("methods"), "peakTravelHours", passengers.get("hourlyVolume"),
                "tripsAndPassengersByBus", buses.get("buses"), "queueLengthByTerminal", terminals.get("queues"));
    }

    private FareRecord fareRecord(Transaction tx) {
        return new FareRecord(tx.getCreatedAt(), tx.getAmount(), transactionBus(tx), normalizeDirection(tx.getRouteSnapshot()),
                transactionPaymentMethod(tx), normalizeTransactionStatus(tx.getStatus()),
                tx.getPassenger() == null ? null : tx.getPassenger().getId(), tx.getReferenceNumber(), tx.getDeviceId());
    }

    private FareRecord cashRecord(StaffCashTransaction tx) {
        return new FareRecord(tx.getCreatedAt(), tx.getFinalFare(), tx.getVehicle() == null ? null : tx.getVehicle().getPlateNumber(),
                normalizeDirection(tx.getRouteSnapshot()), "ASSISTED_CASH", "SUCCESSFUL", null, tx.getReferenceNumber(), tx.getDeviceId());
    }

    private AttemptRecord attemptRecord(FarePaymentAttempt a) {
        Vehicle vehicle = a.getVehicle();
        if (vehicle == null && a.getDriverShift() != null) vehicle = a.getDriverShift().getVehicle();
        return new AttemptRecord(a.getCreatedAt(), vehicle == null ? null : vehicle.getPlateNumber(), normalizeDirection(a.getRouteSnapshot()),
                a.getPaymentMethod() == null ? "UNKNOWN" : a.getPaymentMethod().name(), a.getStatus() == FarePaymentAttemptStatus.SUCCESS,
                a.getFailureReason() == null ? "UNKNOWN" : a.getFailureReason().name(), a.getFailureMessage(), a.getDeviceId(),
                a.getTransaction() == null ? String.valueOf(a.getId()) : a.getTransaction().getReferenceNumber());
    }

    private boolean fareTransaction(Transaction tx) {
        return tx != null && (tx.getType() == TransactionType.FARE_DEDUCTION || tx.getType() == TransactionType.RIDE_FARE);
    }

    private String transactionPaymentMethod(Transaction tx) {
        if (tx.getPaymentMethod() != null) return tx.getPaymentMethod().name();
        String text = ((tx.getDescription() == null ? "" : tx.getDescription()) + " " + (tx.getReferenceNumber() == null ? "" : tx.getReferenceNumber())).toUpperCase(Locale.ROOT);
        if (text.contains("NFC")) return "NFC";
        if (text.contains("QR")) return "QR";
        return "RFID";
    }

    private String transactionBus(Transaction tx) {
        if (tx.getVehicle() != null) return clean(tx.getVehicle().getPlateNumber());
        if (tx.getDriverShift() != null && tx.getDriverShift().getVehicle() != null) return clean(tx.getDriverShift().getVehicle().getPlateNumber());
        if (tx.getDescription() != null && tx.getDescription().contains("|")) {
            String[] parts = tx.getDescription().split("\\|");
            return parts.length > 1 ? clean(parts[1]) : null;
        }
        return null;
    }

    private String normalizeTransactionStatus(TransactionStatus status) {
        if (status == TransactionStatus.SUCCESS || status == TransactionStatus.COMPLETED) return "SUCCESSFUL";
        return status == null ? "UNKNOWN" : status.name();
    }

    private List<FareRecord> successful(List<FareRecord> records) { return records.stream().filter(r -> "SUCCESSFUL".equals(r.status())).toList(); }
    private BigDecimal sum(List<FareRecord> records) { return records.stream().map(FareRecord::amount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private long uniquePassengers(List<FareRecord> records) { return records.stream().map(FareRecord::passengerId).filter(Objects::nonNull).distinct().count(); }

    private Map<String, BusAggregate> aggregateBuses(List<FareRecord> records) {
        return records.stream().filter(r -> r.bus() != null).collect(Collectors.groupingBy(FareRecord::bus, LinkedHashMap::new,
                Collectors.collectingAndThen(Collectors.toList(), rows -> new BusAggregate(rows.get(0).bus(), rows.size(), uniquePassengers(rows), sum(rows)))));
    }

    private Map<String, DirectionAggregate> aggregateDirections(List<FareRecord> records) {
        return records.stream().filter(r -> r.direction() != null).collect(Collectors.groupingBy(FareRecord::direction, LinkedHashMap::new,
                Collectors.collectingAndThen(Collectors.toList(), rows -> new DirectionAggregate(rows.get(0).direction(),
                        directionLabel(rows.get(0).direction()), rows.size(), sum(rows)))));
    }

    private BigDecimal paymentSuccessRate(List<AttemptRecord> attempts, List<FareRecord> records) {
        return attempts.isEmpty() ? (records.isEmpty() ? BigDecimal.ZERO : percent(successful(records).size(), records.size()))
                : percent(attempts.stream().filter(AttemptRecord::successful).count(), attempts.size());
    }

    private Map<String, Object> comparisonMetric(BigDecimal current, BigDecimal previous) {
        return mapOf("current", current, "previous", previous, "available", previous.signum() != 0,
                "percentage", previous.signum() == 0 ? null : current.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous.abs(), 2, RoundingMode.HALF_UP));
    }

    private Map<String, Object> trendSummary(List<Map<String, Object>> rows) {
        Comparator<Map<String, Object>> c = Comparator.comparingLong(r -> ((Number) r.get("passengers")).longValue());
        Map<String, Object> high = rows.stream().max(c).filter(r -> ((Number) r.get("passengers")).longValue() > 0).orElse(null);
        Map<String, Object> low = rows.stream().filter(r -> ((Number) r.get("passengers")).longValue() > 0).min(c).orElse(null);
        return mapOf("highestDay", high, "lowestDay", low, "averageDailyPassengers",
                BigDecimal.valueOf(rows.stream().mapToLong(r -> ((Number) r.get("passengers")).longValue()).average().orElse(0)).setScale(2, RoundingMode.HALF_UP));
    }

    private Map<String, Object> busSummary(BusAggregate b) { return mapOf("bus", b.bus(), "revenue", b.revenue(), "passengers", b.passengers(), "uniquePassengers", b.uniquePassengers()); }
    private Map<String, Object> directionSummary(DirectionAggregate d) { return mapOf("direction", d.code(), "label", d.label(), "passengers", d.passengers(), "revenue", d.revenue()); }
    private Map<String, Object> statusRow(String status, long count, long total) { return mapOf("status", status, "name", title(status), "count", count, "percentage", total == 0 ? BigDecimal.ZERO : percent(count, total)); }

    private Map<String, Object> options(List<Vehicle> vehicles) {
        return mapOf("buses", vehicles.stream().filter(v -> v.getPlateNumber() != null).sorted(Comparator.comparing(Vehicle::getPlateNumber, String.CASE_INSENSITIVE_ORDER))
                        .map(v -> mapOf("id", v.getPlateNumber(), "label", v.getPlateNumber())).toList(),
                "directions", List.of(mapOf("id", SM_TO_GRAND, "label", SM_TO_GRAND_LABEL), mapOf("id", GRAND_TO_SM, "label", GRAND_TO_SM_LABEL)),
                "paymentMethods", List.of(mapOf("id", "RFID", "label", "RFID"), mapOf("id", "NFC", "label", "NFC"),
                        mapOf("id", "QR", "label", "QR"), mapOf("id", "ASSISTED_CASH", "label", "Assisted Cash")),
                "transactionStatuses", List.of("SUCCESSFUL", "FAILED", "PENDING", "CANCELLED"));
    }

    private Map<String, Object> filterMap(DateWindow w, String range, AnalyticsFilter f, ZoneId zone) {
        return mapOf("range", range == null ? "last7" : range, "startDate", w.start().toLocalDate(), "endDate", w.end().toLocalDate(),
                "timezone", zone.getId(), "busId", f.busId(), "direction", f.direction(), "paymentMethod", f.paymentMethod(), "transactionStatus", f.status());
    }

    private DateWindow resolveWindow(String range, LocalDate startDate, LocalDate endDate, ZoneId zone) {
        LocalDate today = LocalDate.now(zone), start, end = today;
        String value = clean(range) == null ? "last7" : range.toLowerCase(Locale.ROOT).replace("_", "");
        switch (value) {
            case "today", "daily", "day" -> start = today;
            case "yesterday" -> start = end = today.minusDays(1);
            case "last30" -> start = today.minusDays(29);
            case "thismonth", "monthly", "month" -> start = today.withDayOfMonth(1);
            case "previousmonth" -> { LocalDate p = today.minusMonths(1); start = p.withDayOfMonth(1); end = p.with(TemporalAdjusters.lastDayOfMonth()); }
            case "custom" -> { if (startDate == null || endDate == null) throw new IllegalArgumentException("Custom date range requires both startDate and endDate."); start = startDate; end = endDate; }
            default -> start = today.minusDays(6);
        }
        if (end.isAfter(today)) throw new IllegalArgumentException("Future date ranges are not supported.");
        if (start.isAfter(end)) throw new IllegalArgumentException("Start date cannot be after end date.");
        if (start.isBefore(today.minusYears(5))) throw new IllegalArgumentException("Analytics date range cannot exceed five years.");
        return new DateWindow(start.atStartOfDay(), end.plusDays(1).atStartOfDay().minusNanos(1));
    }

    private DateWindow dayWindow(LocalDate day) { return new DateWindow(day.atStartOfDay(), day.plusDays(1).atStartOfDay().minusNanos(1)); }
    private DateWindow previousWindow(DateWindow current) { long days = java.time.temporal.ChronoUnit.DAYS.between(current.start().toLocalDate(), current.end().toLocalDate()) + 1; LocalDate end = current.start().toLocalDate().minusDays(1); return new DateWindow(end.minusDays(days - 1).atStartOfDay(), end.plusDays(1).atStartOfDay().minusNanos(1)); }
    private String previousLabel(DateWindow w) { long days = java.time.temporal.ChronoUnit.DAYS.between(w.start().toLocalDate(), w.end().toLocalDate()) + 1; return "previous " + days + (days == 1 ? " day" : " days"); }
    private boolean sameWindow(DateWindow a, DateWindow b) { return a.start().equals(b.start()) && a.end().equals(b.end()); }
    private ZoneId resolveZone(String timezone) { try { return clean(timezone) == null ? DEFAULT_ZONE : ZoneId.of(timezone); } catch (Exception ignored) { return DEFAULT_ZONE; } }
    private boolean busMatches(Vehicle vehicle, String bus) { return bus == null || (vehicle != null && bus.equalsIgnoreCase(vehicle.getPlateNumber())); }
    private boolean offlineDevice(Device d) { return d.getStatus() != DeviceStatus.ACTIVE || d.getRevokedAt() != null || d.getLastSeenAt() == null || d.getLastSeenAt().isBefore(LocalDateTime.now(DEFAULT_ZONE).minusMinutes(OFFLINE_DEVICE_MINUTES)); }
    private boolean missingGps(DriverLocation l) { return l.getRecordedAt() == null || l.getRecordedAt().isBefore(LocalDateTime.now(DEFAULT_ZONE).minusMinutes(MISSING_GPS_MINUTES)); }

    private String normalizeDirection(String value) {
        String clean = blankToNull(value); if (clean == null) return null;
        String n = clean.toUpperCase(Locale.ROOT).replace("\u2192", "_TO_").replace("->", "_TO_").replaceAll("[^A-Z]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (n.equals(SM_TO_GRAND) || n.equals("SM_TERMINAL_TO_GRAND_TERMINAL")) return SM_TO_GRAND;
        if (n.equals(GRAND_TO_SM) || n.equals("GRAND_TERMINAL_TO_SM_TERMINAL")) return GRAND_TO_SM;
        return null;
    }

    private String normalizePaymentMethod(String value) { String clean = blankToNull(value); if (clean == null) return null; String n = clean.toUpperCase(Locale.ROOT).replace(' ', '_'); return n.equals("CASH") ? "ASSISTED_CASH" : n; }
    private String normalizeStatus(String value) { String clean = blankToNull(value); if (clean == null) return null; String n = clean.toUpperCase(Locale.ROOT); return n.equals("SUCCESS") || n.equals("COMPLETED") ? "SUCCESSFUL" : n; }
    private String blankToNull(String value) { String c = clean(value); return c == null || "all".equalsIgnoreCase(c) ? null : c; }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String valueOr(String value, String fallback) { return clean(value) == null ? fallback : value; }
    private String directionLabel(String code) { return SM_TO_GRAND.equals(code) ? SM_TO_GRAND_LABEL : GRAND_TO_SM.equals(code) ? GRAND_TO_SM_LABEL : "Direction unavailable"; }
    private String destinationLabel(String code) { return SM_TO_GRAND.equals(code) ? "Grand Terminal" : GRAND_TO_SM.equals(code) ? "SM Terminal" : "Terminal unavailable"; }
    private String paymentLabel(String method) { return "ASSISTED_CASH".equals(method) ? "Assisted Cash" : valueOr(method, "Unknown"); }

    private String failureLabel(String value) {
        if (value == null) return "Unknown Error";
        return switch (value) { case "INSUFFICIENT_BALANCE" -> "Insufficient Balance"; case "DEVICE_VALIDATION_FAILED" -> "Terminal Error";
            case "EXPIRED_TOKEN", "USED_TOKEN", "INVALID_TOKEN" -> "QR Scan Failure"; case "INVALID_CARD", "BLOCKED_CARD" -> "RFID Read Failure";
            case "INVALID_REQUEST", "DUPLICATE_REQUEST" -> "Request Error"; default -> title(value); };
    }

    private String title(String value) { if (value == null || value.isBlank()) return ""; return java.util.Arrays.stream(value.toLowerCase(Locale.ROOT).split("_")).filter(p -> !p.isBlank()).map(p -> Character.toUpperCase(p.charAt(0)) + p.substring(1)).collect(Collectors.joining(" ")); }
    private String hourLabel(int hour) { int display = hour % 12 == 0 ? 12 : hour % 12; return display + " " + (hour < 12 ? "AM" : "PM"); }
    private String hourRangeLabel(int hour) { return hourLabel(hour) + " \u2013 " + hourLabel((hour + 1) % 24); }
    private String formatDateTime(LocalDateTime value, ZoneId zone) { return value == null ? "" : value.atZone(zone).format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.ENGLISH)); }
    private BigDecimal divide(BigDecimal n, long d) { return d == 0 ? BigDecimal.ZERO : n.divide(BigDecimal.valueOf(d), 2, RoundingMode.HALF_UP); }
    private BigDecimal percent(long n, long d) { return d == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(n).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(d), 2, RoundingMode.HALF_UP); }
    private BigDecimal percentage(BigDecimal n, BigDecimal d) { return d.signum() == 0 ? BigDecimal.ZERO : n.multiply(BigDecimal.valueOf(100)).divide(d, 2, RoundingMode.HALF_UP); }
    private Map<String, Object> mapOf(Object... values) { Map<String, Object> map = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]); return map; }

    private record DateWindow(LocalDateTime start, LocalDateTime end) {}
    private record FareRecord(LocalDateTime createdAt, BigDecimal amount, String bus, String direction, String paymentMethod, String status, Long passengerId, String reference, String deviceId) {}
    private record AttemptRecord(LocalDateTime createdAt, String bus, String direction, String paymentMethod, boolean successful, String failureReason, String failureMessage, String deviceId, String transactionId) {}
    private record BusAggregate(String bus, long passengers, long uniquePassengers, BigDecimal revenue) {}
    private record DirectionAggregate(String code, String label, long passengers, BigDecimal revenue) {}
    private record AnalyticsFilter(String busId, String direction, String paymentMethod, String status) {
        boolean matches(FareRecord r) { return (busId == null || r.bus() != null && busId.equalsIgnoreCase(r.bus())) && (direction == null || direction.equals(r.direction())) && (paymentMethod == null || paymentMethod.equals(r.paymentMethod())) && (status == null || status.equals(r.status())); }
        boolean matches(AttemptRecord r) { String s = r.successful() ? "SUCCESSFUL" : "FAILED"; return (busId == null || r.bus() != null && busId.equalsIgnoreCase(r.bus())) && (direction == null || direction.equals(r.direction())) && (paymentMethod == null || paymentMethod.equals(r.paymentMethod())) && (status == null || status.equals(s)); }
    }
}
