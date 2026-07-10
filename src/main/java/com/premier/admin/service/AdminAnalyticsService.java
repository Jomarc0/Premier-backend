package com.premier.admin.service;

import com.premier.device.model.Device;
import com.premier.device.model.DeviceStatus;
import com.premier.device.repository.DeviceRepository;
import com.premier.driver.model.DriverLocation;
import com.premier.driver.model.DriverShift;
import com.premier.driver.model.PassengerOnboard;
import com.premier.driver.model.ShiftStatus;
import com.premier.driver.model.Vehicle;
import com.premier.driver.model.VehicleStatus;
import com.premier.driver.repository.DriverLocationRepository;
import com.premier.driver.repository.DriverShiftRepository;
import com.premier.driver.repository.PassengerOnboardRepository;
import com.premier.driver.repository.VehicleRepository;
import com.premier.model.TopUpRequest;
import com.premier.model.Transaction;
import com.premier.model.TransactionStatus;
import com.premier.model.TransactionType;
import com.premier.payment.model.FarePaymentAttempt;
import com.premier.payment.model.FarePaymentAttemptStatus;
import com.premier.payment.repository.FarePaymentAttemptRepository;
import com.premier.repository.TopUpRequestRepository;
import com.premier.repository.TransactionRepository;
import com.premier.support.model.SupportTicket;
import com.premier.support.model.SupportTicketStatus;
import com.premier.support.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminAnalyticsService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Manila");
    private static final int ACTIVE_BUS_MINUTES = 15;
    private static final int OFFLINE_DEVICE_MINUTES = 5;
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private final TransactionRepository transactionRepository;
    private final TopUpRequestRepository topUpRequestRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverShiftRepository driverShiftRepository;
    private final PassengerOnboardRepository passengerOnboardRepository;
    private final DriverLocationRepository driverLocationRepository;
    private final DeviceRepository deviceRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final FarePaymentAttemptRepository farePaymentAttemptRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> getAnalytics(String range, LocalDate from, LocalDate to,
                                            String route, String bus) {
        return getDashboard(range, from, to, bus, route, null, DEFAULT_ZONE.getId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDashboard(String range, LocalDate startDate, LocalDate endDate,
                                            String busId, String routeId, String paymentMethod,
                                            String timezone) {
        ZoneId zone = resolveZone(timezone);
        DateWindow window = resolveWindow(range, startDate, endDate, zone);
        String normalizedBus = blankToNull(busId);
        String normalizedRoute = blankToNull(routeId);
        String normalizedPaymentMethod = normalizePaymentMethod(paymentMethod);
        List<Vehicle> vehicles = vehicleRepository.findAll();

        List<Transaction> fareAttempts = transactionRepository.findAll().stream()
                .filter(this::fareTransaction)
                .filter(tx -> inWindow(tx.getCreatedAt(), window))
                .filter(tx -> paymentMethodMatches(tx, normalizedPaymentMethod))
                .filter(tx -> transactionVehicleMatches(tx, normalizedBus, normalizedRoute, vehicles))
                .toList();
        List<Transaction> successfulFare = fareAttempts.stream().filter(this::successful).toList();
        List<FarePaymentAttempt> paymentAttempts = farePaymentAttemptRepository.findByCreatedAtBetween(window.start(), window.end()).stream()
                .filter(attempt -> paymentAttemptMethodMatches(attempt, normalizedPaymentMethod))
                .filter(attempt -> paymentAttemptVehicleMatches(attempt, normalizedBus, normalizedRoute))
                .toList();

        List<PassengerOnboard> onboardRecords = passengerOnboardRepository.findAll().stream()
                .filter(o -> inWindow(o.getBoardedAt(), window))
                .filter(o -> vehicleMatches(o.getShift() == null ? null : o.getShift().getVehicle(), normalizedBus, normalizedRoute))
                .toList();
        List<DriverShift> shifts = driverShiftRepository.findAll().stream()
                .filter(s -> inWindow(s.getShiftStart(), window))
                .filter(s -> vehicleMatches(s.getVehicle(), normalizedBus, normalizedRoute))
                .toList();
        List<DriverLocation> locations = driverLocationRepository.findAll();
        List<Device> devices = deviceRepository.findAll();
        List<TopUpRequest> topUps = topUpRequestRepository.findAll();
        List<SupportTicket> tickets = supportTicketRepository.findAll();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filters", filters(window, range, normalizedBus, normalizedRoute, normalizedPaymentMethod, zone));
        response.put("options", options(vehicles));
        response.put("summary", summary(successfulFare, fareAttempts, paymentAttempts, shifts, vehicles, locations, devices, topUps, tickets, window));
        response.put("charts", charts(successfulFare, fareAttempts, onboardRecords, shifts, vehicles, zone, window));
        response.put("recent", recent(successfulFare, tickets, devices, locations, zone));
        response.put("forecast", forecast(successfulFare));
        response.put("definitions", definitions());
        response.put("unsupported", List.of(
                "Average waiting time is not available because queue entry and service timestamps are not stored.",
                "On-time arrival metrics are not available because scheduled and actual arrival timestamps are not stored.",
                "Driver performance and route efficiency scores were removed because the stored data does not define those scores."
        ));
        response.put("generatedAt", OffsetDateTime.now(zone).toString());
        return response;
    }

    private Map<String, Object> summary(List<Transaction> successfulFare, List<Transaction> fareAttempts,
                                        List<FarePaymentAttempt> paymentAttempts,
                                        List<DriverShift> shifts, List<Vehicle> vehicles,
                                        List<DriverLocation> locations, List<Device> devices,
                                        List<TopUpRequest> topUps, List<SupportTicket> tickets,
                                        DateWindow window) {
        long successfulCount = successfulFare.size();
        long attemptCount = paymentAttempts.isEmpty() ? fareAttempts.size() : paymentAttempts.size();
        long successfulAttempts = paymentAttempts.isEmpty()
                ? successfulCount
                : paymentAttempts.stream().filter(a -> a.getStatus() == FarePaymentAttemptStatus.SUCCESS).count();
        long activePassengers = successfulFare.stream()
                .map(Transaction::getPassenger)
                .filter(Objects::nonNull)
                .map(p -> p.getId())
                .filter(Objects::nonNull)
                .distinct()
                .count();
        long activeBuses = activeBusCount(vehicles, shifts, locations);
        long pendingTopUps = topUps.stream()
                .filter(t -> t.getStatus() == TransactionStatus.PENDING || t.getStatus() == TransactionStatus.PROCESSING)
                .filter(t -> inWindow(t.getCreatedAt(), window))
                .count();
        long openTickets = tickets.stream().filter(this::openTicket).count();
        long offlineDevices = devices.stream().filter(this::offlineDevice).count();

        return mapOf(
                "fareRevenue", sumAmounts(successfulFare),
                "successfulTransactions", successfulCount,
                "activePassengers", activePassengers,
                "activeBuses", activeBuses,
                "paymentSuccessRate", attemptCount == 0 ? 0 : percent(successfulAttempts, attemptCount),
                "pendingTopUps", pendingTopUps,
                "openTickets", openTickets,
                "offlineDevices", offlineDevices
        );
    }

    private Map<String, Object> charts(List<Transaction> successfulFare, List<Transaction> fareAttempts,
                                       List<PassengerOnboard> onboardRecords, List<DriverShift> shifts,
                                       List<Vehicle> vehicles, ZoneId zone, DateWindow window) {
        return mapOf(
                "revenueTrend", revenueTrend(successfulFare, window),
                "transactionsByPaymentMethod", transactionsByPaymentMethod(successfulFare),
                "passengerActivityTrend", passengerActivityTrend(successfulFare),
                "peakTravelHours", peakTravelHours(successfulFare, zone),
                "tripsAndPassengersByBus", tripsAndPassengersByBus(successfulFare, shifts),
                "queueLengthByTerminal", queueLengthByTerminal(vehicles)
        );
    }

    private Map<String, Object> recent(List<Transaction> successfulFare, List<SupportTicket> tickets,
                                       List<Device> devices, List<DriverLocation> locations, ZoneId zone) {
        return mapOf(
                "fareTransactions", successfulFare.stream()
                        .sorted(Comparator.comparing(Transaction::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                        .limit(10)
                        .map(tx -> recentFareTransaction(tx, zone))
                        .toList(),
                "supportTickets", tickets.stream()
                        .sorted(Comparator.comparing(SupportTicket::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                        .limit(10)
                        .map(ticket -> mapOf(
                                "ticketNumber", ticket.getTicketNumber(),
                                "category", label(ticket.getIssueType() == null ? null : ticket.getIssueType().name()),
                                "status", label(ticket.getStatus() == null ? null : ticket.getStatus().name()),
                                "dateSubmitted", formatDateTime(ticket.getCreatedAt(), zone)
                        ))
                        .toList(),
                "systemAlerts", systemAlerts(devices, locations, zone)
        );
    }

    private Map<String, Object> forecast(List<Transaction> successfulFare) {
        long operatingDays = successfulFare.stream()
                .map(Transaction::getCreatedAt)
                .filter(Objects::nonNull)
                .map(LocalDateTime::toLocalDate)
                .distinct()
                .count();
        boolean available = operatingDays >= 30;
        return mapOf(
                "available", available,
                "requiredOperatingDays", 30,
                "currentOperatingDays", operatingDays,
                "message", available
                        ? "Forecasting has enough operating-day history for a future model."
                        : "Forecasting will become available after at least 30 days of valid fare and trip data have been recorded."
        );
    }

    private List<Map<String, Object>> revenueTrend(List<Transaction> successfulFare, DateWindow window) {
        Map<LocalDate, BigDecimal> byDate = successfulFare.stream()
                .filter(tx -> tx.getCreatedAt() != null)
                .collect(Collectors.groupingBy(tx -> tx.getCreatedAt().toLocalDate(), LinkedHashMap::new,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LocalDate day = window.start().toLocalDate(); !day.isAfter(window.end().toLocalDate()); day = day.plusDays(1)) {
            rows.add(mapOf("date", day.format(DAY_KEY), "name", day.format(DAY_LABEL), "revenue", byDate.getOrDefault(day, BigDecimal.ZERO)));
        }
        return rows;
    }

    private List<Map<String, Object>> transactionsByPaymentMethod(List<Transaction> successfulFare) {
        Map<String, List<Transaction>> grouped = successfulFare.stream()
                .collect(Collectors.groupingBy(this::paymentMethod, LinkedHashMap::new, Collectors.toList()));
        BigDecimal totalRevenue = sumAmounts(successfulFare);
        long totalCount = successfulFare.size();
        List<String> methods = List.of("RFID", "QR", "NFC");
        return methods.stream()
                .map(method -> {
                    List<Transaction> txs = grouped.getOrDefault(method, List.of());
                    BigDecimal revenue = sumAmounts(txs);
                    return mapOf(
                            "name", method,
                            "method", method,
                            "count", txs.size(),
                            "revenue", revenue,
                            "share", totalCount == 0 ? 0 : percent(txs.size(), totalCount),
                            "revenueShare", totalRevenue.compareTo(BigDecimal.ZERO) == 0 ? 0 : revenue.multiply(BigDecimal.valueOf(100)).divide(totalRevenue, 2, RoundingMode.HALF_UP)
                    );
                })
                .filter(row -> ((Number) row.get("count")).longValue() > 0)
                .toList();
    }

    private List<Map<String, Object>> passengerActivityTrend(List<Transaction> successfulFare) {
        Map<LocalDate, Long> byDate = successfulFare.stream()
                .filter(tx -> tx.getCreatedAt() != null && tx.getPassenger() != null)
                .collect(Collectors.groupingBy(tx -> tx.getCreatedAt().toLocalDate(), Collectors.mapping(
                        tx -> tx.getPassenger().getId(), Collectors.collectingAndThen(Collectors.toSet(), set -> (long) set.size()))));
        return byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> mapOf("date", e.getKey().format(DAY_KEY), "name", e.getKey().format(DAY_LABEL), "passengers", e.getValue()))
                .toList();
    }

    private List<Map<String, Object>> peakTravelHours(List<Transaction> successfulFare, ZoneId zone) {
        return successfulFare.stream()
                .filter(tx -> tx.getCreatedAt() != null)
                .collect(Collectors.groupingBy(tx -> tx.getCreatedAt().atZone(zone).getHour(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> mapOf("hour", e.getKey(), "name", hourLabel(e.getKey()), "count", e.getValue()))
                .toList();
    }

    private List<Map<String, Object>> tripsAndPassengersByBus(List<Transaction> successfulFare,
                                                              List<DriverShift> shifts) {
        Map<String, Long> completedTrips = shifts.stream()
                .filter(s -> s.getStatus() == ShiftStatus.COMPLETED && s.getVehicle() != null)
                .collect(Collectors.groupingBy(s -> s.getVehicle().getPlateNumber(), Collectors.counting()));
        Map<String, Long> passengers = successfulFare.stream()
                .map(this::transactionBus)
                .filter(bus -> bus != null && !bus.isBlank())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Set<String> plates = new LinkedHashSet<>();
        plates.addAll(completedTrips.keySet());
        plates.addAll(passengers.keySet());
        return plates.stream()
                .sorted()
                .limit(12)
                .map(plate -> mapOf("name", plate, "completedTrips", completedTrips.getOrDefault(plate, 0L),
                        "passengersServed", passengers.getOrDefault(plate, 0L)))
                .toList();
    }

    private List<Map<String, Object>> queueLengthByTerminal(List<Vehicle> vehicles) {
        long active = vehicles.stream().filter(v -> v.getStatus() == VehicleStatus.ACTIVE).count();
        long inactive = vehicles.stream().filter(v -> v.getStatus() != VehicleStatus.ACTIVE).count();
        return List.of(
                terminalQueue("Active route queue", active),
                terminalQueue("Inactive/staging queue", inactive)
        );
    }

    private Map<String, Object> terminalQueue(String name, long count) {
        String status = count >= 21 ? "Congested" : count >= 11 ? "Moderate" : "Normal";
        return mapOf("name", name, "terminal", name, "queueCount", count, "status", status);
    }

    private List<Map<String, Object>> systemAlerts(List<Device> devices, List<DriverLocation> locations, ZoneId zone) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        devices.stream().filter(this::offlineDevice).limit(5).forEach(device -> alerts.add(mapOf(
                "severity", "Critical",
                "title", label(device.getDeviceType() == null ? "Device" : device.getDeviceType().name()) + " offline",
                "message", device.getDeviceName() + " has no valid heartbeat within " + OFFLINE_DEVICE_MINUTES + " minutes.",
                "time", formatDateTime(device.getLastSeenAt(), zone)
        )));
        latestLocationByPlate(locations).values().stream()
                .filter(location -> location.getRecordedAt() != null && location.getRecordedAt().isBefore(LocalDateTime.now().minusMinutes(ACTIVE_BUS_MINUTES)))
                .limit(5)
                .forEach(location -> alerts.add(mapOf(
                        "severity", "Warning",
                        "title", "Missing GPS update",
                        "message", location.getPlateNumber() + " has no GPS update within " + ACTIVE_BUS_MINUTES + " minutes.",
                        "time", formatDateTime(location.getRecordedAt(), zone)
                )));
        return alerts.stream().limit(10).toList();
    }

    private Map<String, Object> recentFareTransaction(Transaction tx, ZoneId zone) {
        return mapOf(
                "time", formatDateTime(tx.getCreatedAt(), zone),
                "maskedCardNumber", tx.getPassenger() == null ? "" : mask(tx.getPassenger().getCardNumber()),
                "paymentMethod", paymentMethod(tx),
                "bus", transactionBus(tx),
                "amount", tx.getAmount(),
                "status", label(tx.getStatus() == null ? null : tx.getStatus().name())
        );
    }

    private long activeBusCount(List<Vehicle> vehicles, List<DriverShift> shifts, List<DriverLocation> locations) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(ACTIVE_BUS_MINUTES);
        Set<String> activePlates = new LinkedHashSet<>();
        shifts.stream()
                .filter(s -> s.getStatus() == ShiftStatus.ACTIVE && s.getVehicle() != null)
                .map(s -> s.getVehicle().getPlateNumber())
                .forEach(activePlates::add);
        latestLocationByPlate(locations).values().stream()
                .filter(l -> l.getRecordedAt() != null && !l.getRecordedAt().isBefore(cutoff))
                .map(DriverLocation::getPlateNumber)
                .forEach(activePlates::add);
        return activePlates.size();
    }

    private boolean fareTransaction(Transaction tx) {
        return tx != null && (tx.getType() == TransactionType.FARE_DEDUCTION || tx.getType() == TransactionType.RIDE_FARE);
    }

    private boolean successful(Transaction tx) {
        return tx.getStatus() == TransactionStatus.SUCCESS || tx.getStatus() == TransactionStatus.COMPLETED;
    }

    private boolean openTicket(SupportTicket ticket) {
        return ticket.getStatus() == SupportTicketStatus.PENDING || ticket.getStatus() == SupportTicketStatus.IN_REVIEW;
    }

    private boolean offlineDevice(Device device) {
        if (device.getStatus() != DeviceStatus.ACTIVE || device.getRevokedAt() != null) {
            return false;
        }
        return device.getLastSeenAt() == null || device.getLastSeenAt().isBefore(LocalDateTime.now().minusMinutes(OFFLINE_DEVICE_MINUTES));
    }

    private boolean inWindow(LocalDateTime value, DateWindow window) {
        return value != null && !value.isBefore(window.start()) && !value.isAfter(window.end());
    }

    private boolean vehicleMatches(Vehicle vehicle, String bus, String route) {
        if (vehicle == null) return bus == null && route == null;
        boolean busMatches = bus == null || bus.equalsIgnoreCase(vehicle.getPlateNumber()) || bus.equals(String.valueOf(vehicle.getId()));
        boolean routeMatches = route == null || route.equalsIgnoreCase(vehicle.getRoute());
        return busMatches && routeMatches;
    }

    private boolean transactionVehicleMatches(Transaction tx, String bus, String route, List<Vehicle> vehicles) {
        if (bus == null && route == null) return true;
        String txBus = transactionBus(tx);
        if (txBus == null || txBus.isBlank()) return false;
        if (bus != null && !txBus.equalsIgnoreCase(bus)) return false;
        if (route == null) return true;
        String routeSnapshot = tx.getRouteSnapshot();
        if (routeSnapshot != null && route.equalsIgnoreCase(routeSnapshot)) {
            return true;
        }
        return tx.getVehicle() != null
                ? route.equalsIgnoreCase(tx.getVehicle().getRoute())
                : vehicles.stream()
                .filter(vehicle -> vehicle.getPlateNumber() != null && vehicle.getPlateNumber().equalsIgnoreCase(txBus))
                .anyMatch(vehicle -> route.equalsIgnoreCase(vehicle.getRoute()));
    }

    private boolean paymentMethodMatches(Transaction tx, String method) {
        return method == null || paymentMethod(tx).equalsIgnoreCase(method);
    }

    private boolean paymentAttemptMethodMatches(FarePaymentAttempt attempt, String method) {
        return method == null || (attempt.getPaymentMethod() != null
                && attempt.getPaymentMethod().name().equalsIgnoreCase(method));
    }

    private boolean paymentAttemptVehicleMatches(FarePaymentAttempt attempt, String bus, String route) {
        if (bus == null && route == null) return true;
        Vehicle vehicle = attempt.getVehicle();
        if (vehicle == null && attempt.getDriverShift() != null) {
            vehicle = attempt.getDriverShift().getVehicle();
        }
        String plate = vehicle == null ? null : vehicle.getPlateNumber();
        if (bus != null && (plate == null || !bus.equalsIgnoreCase(plate))) {
            return false;
        }
        if (route == null) {
            return true;
        }
        if (attempt.getRouteSnapshot() != null && route.equalsIgnoreCase(attempt.getRouteSnapshot())) {
            return true;
        }
        return vehicle != null && route.equalsIgnoreCase(vehicle.getRoute());
    }

    private String paymentMethod(Transaction tx) {
        if (tx.getPaymentMethod() != null) {
            return tx.getPaymentMethod().name();
        }
        String description = tx.getDescription() == null ? "" : tx.getDescription().toUpperCase(Locale.ROOT);
        String reference = tx.getReferenceNumber() == null ? "" : tx.getReferenceNumber().toUpperCase(Locale.ROOT);
        if (description.contains("NFC") || reference.startsWith("NFC-")) return "NFC";
        if (description.contains("QR") || reference.startsWith("QR-")) return "QR";
        return "RFID";
    }

    private String transactionBus(Transaction tx) {
        if (tx.getVehicle() != null && tx.getVehicle().getPlateNumber() != null) {
            return tx.getVehicle().getPlateNumber();
        }
        if (tx.getDriverShift() != null
                && tx.getDriverShift().getVehicle() != null
                && tx.getDriverShift().getVehicle().getPlateNumber() != null) {
            return tx.getDriverShift().getVehicle().getPlateNumber();
        }
        return busFromDescription(tx);
    }

    private String busFromDescription(Transaction tx) {
        if (tx.getDescription() == null || !tx.getDescription().contains("|")) {
            return "";
        }
        String[] parts = tx.getDescription().split("\\|");
        return parts.length < 2 ? "" : parts[1].trim();
    }

    private Map<String, DriverLocation> latestLocationByPlate(List<DriverLocation> locations) {
        return locations.stream()
                .filter(l -> l.getPlateNumber() != null && l.getRecordedAt() != null)
                .collect(Collectors.toMap(l -> l.getPlateNumber().toUpperCase(Locale.ROOT), Function.identity(),
                        (a, b) -> a.getRecordedAt().isAfter(b.getRecordedAt()) ? a : b));
    }

    private DateWindow resolveWindow(String range, LocalDate startDate, LocalDate endDate, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        String value = range == null ? "last7" : range.toLowerCase(Locale.ROOT);
        LocalDate start;
        LocalDate end = today;
        switch (value) {
            case "today", "daily", "day" -> start = today;
            case "last30" -> start = today.minusDays(29);
            case "thismonth", "monthly", "month" -> start = today.withDayOfMonth(1);
            case "custom" -> {
                start = startDate == null ? today.minusDays(6) : startDate;
                end = endDate == null ? today : endDate;
            }
            default -> start = today.minusDays(6);
        }
        if (end.isAfter(today)) {
            throw new IllegalArgumentException("Future date ranges are not supported.");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Start date cannot be after end date.");
        }
        return new DateWindow(start.atStartOfDay(), end.plusDays(1).atStartOfDay().minusNanos(1));
    }

    private ZoneId resolveZone(String timezone) {
        try {
            return timezone == null || timezone.isBlank() ? DEFAULT_ZONE : ZoneId.of(timezone);
        } catch (Exception ignored) {
            return DEFAULT_ZONE;
        }
    }

    private Map<String, Object> options(List<Vehicle> vehicles) {
        List<Map<String, Object>> buses = vehicles.stream()
                .sorted(Comparator.comparing(Vehicle::getPlateNumber, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(v -> mapOf("id", v.getPlateNumber(), "label", v.getPlateNumber()))
                .toList();
        List<Map<String, Object>> routes = vehicles.stream()
                .map(Vehicle::getRoute)
                .filter(route -> route != null && !route.isBlank())
                .distinct()
                .sorted()
                .map(route -> mapOf("id", route, "label", route))
                .toList();
        return mapOf("buses", buses, "routes", routes, "paymentMethods", List.of("RFID", "QR", "NFC"));
    }

    private Map<String, Object> filters(DateWindow window, String range, String bus, String route, String paymentMethod, ZoneId zone) {
        return mapOf(
                "range", range == null ? "last7" : range,
                "startDate", window.start().toLocalDate(),
                "endDate", window.end().toLocalDate(),
                "timezone", zone.getId(),
                "busId", bus,
                "routeId", route,
                "paymentMethod", paymentMethod
        );
    }

    private Map<String, Object> definitions() {
        return mapOf(
                "fareRevenue", "Sum of successful FARE_DEDUCTION and RIDE_FARE transactions only.",
                "successfulFareTransaction", "Transaction status SUCCESS or COMPLETED and type FARE_DEDUCTION or RIDE_FARE.",
                "activePassenger", "Distinct passenger with at least one successful fare transaction in the selected period.",
                "activeBus", "Vehicle with active status, active shift, or GPS update within " + ACTIVE_BUS_MINUTES + " minutes.",
                "pendingTopUp", "Top-up request with PENDING or PROCESSING status.",
                "openTicket", "Support ticket with PENDING or IN_REVIEW status.",
                "offlineDevice", "Active non-revoked device with no heartbeat within " + OFFLINE_DEVICE_MINUTES + " minutes."
        );
    }

    private BigDecimal sumAmounts(List<Transaction> transactions) {
        return transactions.stream()
                .map(Transaction::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal percent(long numerator, long denominator) {
        if (denominator == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private String formatDateTime(LocalDateTime value, ZoneId zone) {
        if (value == null) return "";
        return value.atZone(zone).format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.ENGLISH));
    }

    private String hourLabel(int hour) {
        int display = hour % 12 == 0 ? 12 : hour % 12;
        return display + " " + (hour < 12 ? "AM" : "PM");
    }

    private String label(String value) {
        if (value == null || value.isBlank()) return "";
        String[] parts = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return "";
        String trimmed = value.trim();
        int visible = Math.min(4, trimmed.length());
        return "*".repeat(Math.max(0, trimmed.length() - visible)) + trimmed.substring(trimmed.length() - visible);
    }

    private String normalizePaymentMethod(String value) {
        String clean = blankToNull(value);
        if (clean == null || "all".equalsIgnoreCase(clean)) return null;
        return clean.toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() || "all".equalsIgnoreCase(value) ? null : value.trim();
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
