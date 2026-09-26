package com.premier.staffqueue.service;

import com.premier.admin.model.Admin;
import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import com.premier.exception.ClientException;
import com.premier.realtime.RealtimeEventPublisher;
import com.premier.staffqueue.model.*;
import com.premier.staffqueue.repository.TerminalQueueEntryRepository;
import com.premier.staffqueue.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BusQueueService {
    /** Informational visibility starts at 1 km; persistent FIFO membership starts at 50 m. */
    static final double APPROACHING_RADIUS_KM = 1.00;
    static final double CHECK_IN_RADIUS_KM = 0.05;
    static final double REARM_RADIUS_KM = 0.30;
    static final long LIVE_GPS_SECONDS = 45;
    private static final List<TerminalQueueStatus> ACTIVE =
            List.of(TerminalQueueStatus.WAITING, TerminalQueueStatus.BOARDING);

    private final TerminalQueueEntryRepository queues;
    private final VehicleRepository vehicles;
    private final DriverLocationRepository locations;
    private final com.premier.device.repository.DeviceRepository devices;
    private final RealtimeEventPublisher events;

    @org.springframework.beans.factory.annotation.Value("${premier.terminals.sm.latitude:13.954781}")
    private double smTerminalLatitude;
    @org.springframework.beans.factory.annotation.Value("${premier.terminals.sm.longitude:121.163096}")
    private double smTerminalLongitude;
    @org.springframework.beans.factory.annotation.Value("${premier.terminals.grand.latitude:13.790391}")
    private double grandTerminalLatitude;
    @org.springframework.beans.factory.annotation.Value("${premier.terminals.grand.longitude:121.062721}")
    private double grandTerminalLongitude;

    @Transactional(readOnly = true)
    public BusQueueDashboardResponse getDashboard() {
        List<TerminalQueueEntry> active = queues.findByStatusInOrderByCheckedInAtAscIdAsc(ACTIVE);
        List<Vehicle> activeVehicles = vehicles.findByStatus(VehicleStatus.ACTIVE);
        Set<Long> queuedVehicleIds = active.stream().map(row -> row.getVehicle().getId()).collect(Collectors.toSet());
        Map<String, DriverLocation> latest = latestLocations(active, activeVehicles);
        Map<String, com.premier.device.model.Device> deviceById = latestDevices(latest.values());
        Map<TerminalCode, List<ApproachingBusResponse>> approaching = approachingVehicles(
                activeVehicles, queuedVehicleIds, latest, deviceById);
        TerminalQueueResponse sm = terminalView(TerminalCode.SM_TERMINAL, active, latest, deviceById,
                approaching.getOrDefault(TerminalCode.SM_TERMINAL, List.of()));
        TerminalQueueResponse grand = terminalView(TerminalCode.GRAND_TERMINAL, active, latest, deviceById,
                approaching.getOrDefault(TerminalCode.GRAND_TERMINAL, List.of()));
        return new BusQueueDashboardResponse(LocalDateTime.now(), sm.waiting(), grand.waiting(), sm, grand);
    }

    @Transactional(readOnly = true)
    public List<EligibleQueueVehicleResponse> eligibleVehicles() {
        Set<Long> activeIds = queues.findByStatusInOrderByCheckedInAtAscIdAsc(ACTIVE).stream()
                .map(row -> row.getVehicle().getId()).collect(Collectors.toSet());
        return vehicles.findByStatus(VehicleStatus.ACTIVE).stream()
                .filter(vehicle -> !activeIds.contains(vehicle.getId()))
                .sorted(Comparator.comparing(Vehicle::getPlateNumber, String.CASE_INSENSITIVE_ORDER))
                .map(vehicle -> new EligibleQueueVehicleResponse(
                        vehicle.getId(), vehicle.getPlateNumber(), vehicle.getStatus().name()))
                .toList();
    }

    @Transactional
    public BusQueueItemResponse manualCheckIn(TerminalCode terminal, Long vehicleId, Admin staff) {
        if (staff == null || staff.getId() == null) throw unauthorized();
        Vehicle vehicle = activeVehicle(vehicleId);
        if (queues.existsByVehicleIdAndStatusIn(vehicleId, ACTIVE)) {
            throw conflict("This vehicle is already active in a terminal queue.");
        }
        try {
            TerminalQueueEntry entry = queues.saveAndFlush(newEntry(terminal, vehicle, QueueCheckInSource.STAFF, staff));
            publish(entry.getId());
            return item(entry, null, null);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("This vehicle is already active in a terminal queue.");
        }
    }

    /** Called only by the authenticated GPS ingestion flow. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> checkInFromGps(Long vehicleId, double latitude, double longitude) {
        Vehicle vehicle = vehicles.findById(vehicleId).orElse(null);
        if (vehicle == null || vehicle.getStatus() != VehicleStatus.ACTIVE) return Optional.empty();
        Optional<TerminalCode> arrival = arrivalTerminal(latitude, longitude);
        Optional<TerminalQueueEntry> latest = queues.findTopByVehicleIdOrderByCheckedInAtDescIdDesc(vehicleId);
        if (arrival.isEmpty()) {
            if (outsideRearmZones(latitude, longitude)) {
                latest.filter(row -> !ACTIVE.contains(row.getStatus()) && row.getRearmedAt() == null)
                        .ifPresent(row -> row.setRearmedAt(Instant.now()));
            }
            return Optional.empty();
        }
        if (queues.existsByVehicleIdAndStatusIn(vehicleId, ACTIVE)) return Optional.empty();
        if (latest.isPresent() && latest.get().getTerminal() == arrival.get()
                && latest.get().getRearmedAt() == null) return Optional.empty();
        try {
            TerminalQueueEntry entry = queues.saveAndFlush(
                    newEntry(arrival.get(), vehicle, QueueCheckInSource.GPS, null));
            publish(entry.getId());
            return Optional.of(entry.getId());
        } catch (DataIntegrityViolationException duplicate) {
            return Optional.empty();
        }
    }

    @Transactional
    public BusQueueItemResponse startBoarding(Long id) {
        try {
            TerminalCode terminal = queues.findById(id)
                    .orElseThrow(() -> new ClientException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Queue entry not found."))
                    .getTerminal();
            TerminalQueueEntry first = queues.findFirstByTerminalAndStatusOrderByCheckedInAtAscIdAsc(
                            terminal, TerminalQueueStatus.WAITING)
                    .orElseThrow(() -> conflict("There is no waiting vehicle at this terminal."));
            if (!first.getId().equals(id)) throw conflict("Only the first waiting vehicle can start boarding.");
            if (queues.existsByTerminalAndStatus(terminal, TerminalQueueStatus.BOARDING)) {
                throw conflict("Another vehicle is already boarding at this terminal.");
            }
            first.setStatus(TerminalQueueStatus.BOARDING);
            first.setBoardingAt(Instant.now());
            queues.saveAndFlush(first);
            publish(first.getId());
            return item(first, null, null);
        } catch (DataIntegrityViolationException | org.springframework.dao.PessimisticLockingFailureException ex) {
            throw conflict("Another vehicle is already boarding at this terminal.");
        }
    }

    @Transactional
    public BusQueueItemResponse markDeparted(Long id) {
        TerminalQueueEntry entry = locked(id);
        if (entry.getStatus() != TerminalQueueStatus.BOARDING) {
            throw conflict("Only a boarding vehicle can be marked departed.");
        }
        entry.setStatus(TerminalQueueStatus.DEPARTED);
        entry.setDepartedAt(Instant.now());
        queues.save(entry);
        publish(entry.getId());
        return item(entry, null, null);
    }

    @Transactional
    public BusQueueItemResponse cancel(Long id) {
        TerminalQueueEntry entry = locked(id);
        if (!ACTIVE.contains(entry.getStatus())) throw conflict("This queue entry is no longer active.");
        entry.setStatus(TerminalQueueStatus.CANCELLED);
        entry.setCancelledAt(Instant.now());
        queues.save(entry);
        publish(entry.getId());
        return item(entry, null, null);
    }

    private TerminalQueueEntry locked(Long id) {
        return queues.findLockedById(id)
                .orElseThrow(() -> new ClientException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Queue entry not found."));
    }

    private Vehicle activeVehicle(Long id) {
        Vehicle vehicle = vehicles.findById(id)
                .orElseThrow(() -> new ClientException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Vehicle not found."));
        if (vehicle.getStatus() != VehicleStatus.ACTIVE) {
            throw new ClientException(HttpStatus.UNPROCESSABLE_ENTITY, "VEHICLE_INACTIVE",
                    "Only an active vehicle can enter the terminal queue.");
        }
        return vehicle;
    }

    private TerminalQueueEntry newEntry(TerminalCode terminal, Vehicle vehicle,
                                        QueueCheckInSource source, Admin staff) {
        return TerminalQueueEntry.builder().terminal(terminal).vehicle(vehicle)
                .vehiclePlateNumber(vehicle.getPlateNumber()).status(TerminalQueueStatus.WAITING)
                .checkInSource(source).checkedInBy(staff).checkedInAt(Instant.now()).build();
    }

    private TerminalQueueResponse terminalView(TerminalCode terminal, List<TerminalQueueEntry> entries,
                                                Map<String, DriverLocation> latest,
                                                 Map<String, com.premier.device.model.Device> deviceById,
                                                 List<ApproachingBusResponse> approaching) {
        List<TerminalQueueEntry> rows = entries.stream().filter(row -> row.getTerminal() == terminal).toList();
        BusQueueItemResponse boarding = rows.stream().filter(row -> row.getStatus() == TerminalQueueStatus.BOARDING)
                .findFirst().map(row -> item(row, latest.get(row.getVehiclePlateNumber()), deviceById)).orElse(null);
        java.util.concurrent.atomic.AtomicInteger position = new java.util.concurrent.atomic.AtomicInteger(1);
        List<BusQueueItemResponse> waiting = rows.stream().filter(row -> row.getStatus() == TerminalQueueStatus.WAITING)
                .sorted(Comparator.comparing(TerminalQueueEntry::getCheckedInAt).thenComparing(TerminalQueueEntry::getId))
                .map(row -> item(row, latest.get(row.getVehiclePlateNumber()), deviceById, position.getAndIncrement())).toList();
        return new TerminalQueueResponse(terminal, boarding, waiting, approaching);
    }

    private BusQueueItemResponse item(TerminalQueueEntry entry, DriverLocation location,
                                      Map<String, com.premier.device.model.Device> deviceById) {
        return item(entry, location, deviceById, null);
    }

    private BusQueueItemResponse item(TerminalQueueEntry entry, DriverLocation location,
                                      Map<String, com.premier.device.model.Device> deviceById, Integer position) {
        com.premier.device.model.Device device = location == null || deviceById == null ? null : deviceById.get(location.getDeviceId());
        boolean live = liveGps(entry.getVehiclePlateNumber(), location, device);
        Double distance = live ? round1(distanceTo(entry.getTerminal(), location.getLatitude(), location.getLongitude())) : null;
        Long eta = live && location.getSpeed() != null && Double.isFinite(location.getSpeed()) && location.getSpeed() > 0
                ? Math.max(1L, Math.round(distance / location.getSpeed() * 60)) : null;
        String route = entry.getTerminal() == TerminalCode.SM_TERMINAL
                ? "Grand Terminal to SM Terminal" : "SM Terminal to Grand Terminal";
        BusQueueStatus display = entry.getStatus() == TerminalQueueStatus.BOARDING
                ? BusQueueStatus.BOARDING : BusQueueStatus.WAITING;
        return new BusQueueItemResponse(entry.getId(), entry.getTerminal(), entry.getVehicle().getId(),
                entry.getVehiclePlateNumber(), entry.getVehicle().getStatus(), route, distance, eta, position,
                display, entry.getStatus(), entry.getCheckInSource(), display.getLabel(), entry.getCheckedInAt(),
                entry.getBoardingAt(), entry.getDepartedAt(), live ? location.getLatitude() : null,
                live ? location.getLongitude() : null, live, location == null ? null : location.getCapturedAt());
    }

    private Map<String, DriverLocation> latestLocations(List<TerminalQueueEntry> active,
                                                        List<Vehicle> activeVehicles) {
        List<String> plates = java.util.stream.Stream.concat(
                        active.stream().map(TerminalQueueEntry::getVehiclePlateNumber),
                        activeVehicles.stream().map(Vehicle::getPlateNumber))
                .filter(Objects::nonNull).distinct().toList();
        if (plates.isEmpty()) return Map.of();
        return locations.findLatestForPlates(plates).stream().collect(Collectors.toMap(
                DriverLocation::getPlateNumber, Function.identity(), (a, b) -> a));
    }

    private Map<String, com.premier.device.model.Device> latestDevices(Collection<DriverLocation> rows) {
        List<String> ids = rows.stream().map(DriverLocation::getDeviceId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return devices.findByDeviceIdIn(ids).stream().collect(Collectors.toMap(
                com.premier.device.model.Device::getDeviceId, Function.identity(), (a, b) -> a));
    }

    private boolean liveGps(String plate, DriverLocation location, com.premier.device.model.Device device) {
        Instant now = Instant.now();
        return location != null && device != null && device.isActive() && "GPS_VALID".equals(device.getGpsState())
                && Objects.equals(device.getPlateNumber(), plate) && location.getCapturedAt() != null
                && location.getCapturedAt().isAfter(now.minusSeconds(LIVE_GPS_SECONDS))
                && !location.getCapturedAt().isAfter(now.plusSeconds(10));
    }

    private Map<TerminalCode, List<ApproachingBusResponse>> approachingVehicles(
            List<Vehicle> activeVehicles, Set<Long> queuedVehicleIds,
            Map<String, DriverLocation> latest,
            Map<String, com.premier.device.model.Device> deviceById) {
        Map<TerminalCode, List<ApproachingBusResponse>> result = new EnumMap<>(TerminalCode.class);
        for (Vehicle vehicle : activeVehicles) {
            if (queuedVehicleIds.contains(vehicle.getId())) continue;
            DriverLocation location = latest.get(vehicle.getPlateNumber());
            com.premier.device.model.Device device = location == null ? null : deviceById.get(location.getDeviceId());
            if (!liveGps(vehicle.getPlateNumber(), location, device)
                    || !Objects.equals(device.getVehicleId(), vehicle.getId())) continue;

            double smDistance = distanceTo(TerminalCode.SM_TERMINAL, location.getLatitude(), location.getLongitude());
            double grandDistance = distanceTo(TerminalCode.GRAND_TERMINAL, location.getLatitude(), location.getLongitude());
            double nearestDistance = Math.min(smDistance, grandDistance);
            if (nearestDistance <= CHECK_IN_RADIUS_KM || nearestDistance > APPROACHING_RADIUS_KM) continue;

            TerminalCode terminal = smDistance <= grandDistance
                    ? TerminalCode.SM_TERMINAL : TerminalCode.GRAND_TERMINAL;
            String route = terminal == TerminalCode.SM_TERMINAL
                    ? "Grand Terminal to SM Terminal" : "SM Terminal to Grand Terminal";
            result.computeIfAbsent(terminal, ignored -> new ArrayList<>()).add(
                    new ApproachingBusResponse(terminal, vehicle.getId(), vehicle.getPlateNumber(),
                            vehicle.getStatus(), route, round2(nearestDistance), true, location.getCapturedAt()));
        }
        result.values().forEach(rows -> rows.sort(Comparator
                .comparing(ApproachingBusResponse::distanceKm)
                .thenComparing(ApproachingBusResponse::plateNumber, String.CASE_INSENSITIVE_ORDER)));
        return result;
    }

    private Optional<TerminalCode> arrivalTerminal(double latitude, double longitude) {
        double sm = distanceKm(latitude, longitude, smTerminalLatitude, smTerminalLongitude);
        double grand = distanceKm(latitude, longitude, grandTerminalLatitude, grandTerminalLongitude);
        if (Math.min(sm, grand) > CHECK_IN_RADIUS_KM) return Optional.empty();
        return Optional.of(sm <= grand ? TerminalCode.SM_TERMINAL : TerminalCode.GRAND_TERMINAL);
    }

    private boolean outsideRearmZones(double latitude, double longitude) {
        return distanceKm(latitude, longitude, smTerminalLatitude, smTerminalLongitude) > REARM_RADIUS_KM
                && distanceKm(latitude, longitude, grandTerminalLatitude, grandTerminalLongitude) > REARM_RADIUS_KM;
    }

    private double distanceTo(TerminalCode terminal, double latitude, double longitude) {
        return terminal == TerminalCode.SM_TERMINAL
                ? distanceKm(latitude, longitude, smTerminalLatitude, smTerminalLongitude)
                : distanceKm(latitude, longitude, grandTerminalLatitude, grandTerminalLongitude);
    }

    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        return com.premier.device.service.GpsTelemetryService.distanceKm(lat1, lon1, lat2, lon2);
    }

    private double round1(double value) { return Math.round(value * 10.0) / 10.0; }
    private double round2(double value) { return Math.round(value * 100.0) / 100.0; }
    private ClientException conflict(String message) { return new ClientException(HttpStatus.CONFLICT, "QUEUE_CONFLICT", message); }
    private ClientException unauthorized() { return new ClientException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Staff authentication is required."); }
    private void publish(Long id) { events.staff("TERMINAL_QUEUE_UPDATED", "TERMINAL_QUEUE", id); }
}
