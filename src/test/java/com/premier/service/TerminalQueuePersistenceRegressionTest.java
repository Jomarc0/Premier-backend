package com.premier.service;

import com.premier.admin.model.*;
import com.premier.admin.repository.AdminRepository;
import com.premier.device.model.*;
import com.premier.device.repository.DeviceRepository;
import com.premier.device.request.GpsTelemetryRequest;
import com.premier.device.security.DevicePrincipal;
import com.premier.device.service.GpsTelemetryService;
import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import com.premier.staffqueue.model.*;
import com.premier.staffqueue.repository.TerminalQueueEntryRepository;
import com.premier.staffqueue.service.BusQueueService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class TerminalQueuePersistenceRegressionTest {
    private static final double GRAND_LAT = 13.790391;
    private static final double GRAND_LNG = 121.062721;
    private static final double SM_LAT = 13.954781;
    private static final double SM_LNG = 121.163096;

    @Autowired BusQueueService queue;
    @Autowired GpsTelemetryService gps;
    @Autowired TerminalQueueEntryRepository entries;
    @Autowired VehicleRepository vehicles;
    @Autowired DeviceRepository devices;
    @Autowired DriverLocationRepository locations;
    @Autowired AdminRepository admins;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void clearQueue() {
        entries.deleteAll();
        entries.flush();
    }

    @Test
    void authenticatedGpsCreatesOnePersistentEntryWithoutDriverShiftOrTrip() {
        Fixture fixture = fixture("GPS");
        gps.receive(fixture.device(), fix(fixture, GRAND_LAT, GRAND_LNG));
        TerminalQueueEntry first = entries.findAll().get(0);
        Instant checkedInAt = first.getCheckedInAt();

        for (int i = 0; i < 20; i++) {
            gps.receive(fixture.device(), fix(fixture, GRAND_LAT, GRAND_LNG));
        }

        assertThat(entries.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getTerminal()).isEqualTo(TerminalCode.GRAND_TERMINAL);
            assertThat(row.getStatus()).isEqualTo(TerminalQueueStatus.WAITING);
            assertThat(row.getCheckInSource()).isEqualTo(QueueCheckInSource.GPS);
            assertThat(row.getCheckedInAt()).isEqualTo(checkedInAt);
            assertThat(row.getVehicle().getId()).isEqualTo(fixture.vehicle().getId());
        });
    }

    @Test
    void staleOrMissingGpsNeverRemovesPersistentQueueMembership() {
        Fixture fixture = fixture("STALE");
        queue.checkInFromGps(fixture.vehicle().getId(), GRAND_LAT, GRAND_LNG);
        var stale = DriverLocation.builder().plateNumber(fixture.vehicle().getPlateNumber())
                .deviceId(fixture.device().deviceId()).latitude(GRAND_LAT).longitude(GRAND_LNG)
                .capturedAt(Instant.now().minus(Duration.ofMinutes(5))).recordedAt(LocalDateTime.now().minusMinutes(5)).build();
        locations.saveAndFlush(stale);

        var row = queue.getDashboard().grandTerminal().waiting().get(0);

        assertThat(row.plateNumber()).isEqualTo(fixture.vehicle().getPlateNumber());
        assertThat(row.gpsAvailable()).isFalse();
        assertThat(row.distanceKm()).isNull();
        assertThat(row.etaMinutes()).isNull();
    }

    @Test
    void fifoBoardingDepartureAndCancellationPreserveHistory() {
        Vehicle first = vehicle("FIFO-A");
        Vehicle second = vehicle("FIFO-B");
        Vehicle third = vehicle("FIFO-C");
        saveEntry(first, TerminalCode.GRAND_TERMINAL, Instant.parse("2026-09-25T02:00:00Z"));
        saveEntry(second, TerminalCode.GRAND_TERMINAL, Instant.parse("2026-09-25T02:02:00Z"));
        saveEntry(third, TerminalCode.GRAND_TERMINAL, Instant.parse("2026-09-25T02:04:00Z"));

        var initial = queue.getDashboard().grandTerminal();
        assertThat(initial.waiting()).extracting(row -> row.plateNumber())
                .containsExactly(first.getPlateNumber(), second.getPlateNumber(), third.getPlateNumber());
        assertThat(initial.waiting()).extracting(row -> row.queuePosition()).containsExactly(1, 2, 3);

        Long firstId = initial.waiting().get(0).id();
        Long secondId = initial.waiting().get(1).id();
        queue.startBoarding(firstId);
        assertThatThrownBy(() -> queue.startBoarding(secondId)).hasMessageContaining("already boarding");
        var boarding = queue.getDashboard().grandTerminal();
        assertThat(boarding.boarding().plateNumber()).isEqualTo(first.getPlateNumber());
        assertThat(boarding.boarding().queuePosition()).isNull();
        assertThat(boarding.waiting()).extracting(row -> row.queuePosition()).containsExactly(1, 2);

        queue.markDeparted(firstId);
        queue.cancel(secondId);
        assertThat(queue.getDashboard().grandTerminal().waiting()).singleElement()
                .satisfies(row -> assertThat(row.plateNumber()).isEqualTo(third.getPlateNumber()));
        assertThat(entries.findById(firstId).orElseThrow().getStatus()).isEqualTo(TerminalQueueStatus.DEPARTED);
        assertThat(entries.findById(secondId).orElseThrow().getStatus()).isEqualTo(TerminalQueueStatus.CANCELLED);
    }

    @Test
    void manualCheckInUsesVehicleAndAuthenticatedStaffAndRejectsCrossTerminalDuplicate() {
        Vehicle vehicle = vehicle("MANUAL");
        Admin staff = staff();
        var checkedIn = queue.manualCheckIn(TerminalCode.GRAND_TERMINAL, vehicle.getId(), staff);

        assertThat(checkedIn.checkInSource()).isEqualTo(QueueCheckInSource.STAFF);
        assertThat(entries.findById(checkedIn.id()).orElseThrow().getCheckedInBy().getId()).isEqualTo(staff.getId());
        assertThatThrownBy(() -> queue.manualCheckIn(TerminalCode.SM_TERMINAL, vehicle.getId(), staff))
                .hasMessageContaining("already active");
    }

    @Test
    void departedVehicleCanReturnAtOppositeTerminalButCannotRecheckWhileStillUnderSameRoof() {
        Vehicle vehicle = vehicle("RETURN");
        queue.checkInFromGps(vehicle.getId(), GRAND_LAT, GRAND_LNG);
        Long id = queue.getDashboard().grandTerminal().waiting().get(0).id();
        queue.startBoarding(id);
        queue.markDeparted(id);

        assertThat(queue.checkInFromGps(vehicle.getId(), GRAND_LAT, GRAND_LNG)).isEmpty();
        assertThat(queue.checkInFromGps(vehicle.getId(), SM_LAT, SM_LNG)).isPresent();
        assertThat(queue.getDashboard().smTerminal().waiting()).singleElement()
                .satisfies(row -> assertThat(row.plateNumber()).isEqualTo(vehicle.getPlateNumber()));
        assertThat(entries.findAll()).hasSize(2);
    }

    @Test
    void concurrentBoardingLeavesOnlyOneBoardingVehicle() throws Exception {
        Vehicle first = vehicle("RACE-A");
        Vehicle second = vehicle("RACE-B");
        Long firstId = saveEntry(first, TerminalCode.SM_TERMINAL, Instant.now().minusSeconds(10)).getId();
        Long secondId = saveEntry(second, TerminalCode.SM_TERMINAL, Instant.now()).getId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> results = List.of(
                    executor.submit(() -> invokeBoarding(firstId, start)),
                    executor.submit(() -> invokeBoarding(secondId, start)));
            start.countDown();
            for (Future<Object> result : results) result.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(entries.findAll().stream().filter(row -> row.getStatus() == TerminalQueueStatus.BOARDING)).hasSize(1);
    }

    @Test
    void queueSurvivesPersistenceBoundaryWithoutGps() {
        Vehicle vehicle = vehicle("RESTART");
        saveEntry(vehicle, TerminalCode.SM_TERMINAL, Instant.now());
        entityManager.clear();

        assertThat(queue.getDashboard().smTerminal().waiting()).singleElement()
                .satisfies(row -> {
                    assertThat(row.plateNumber()).isEqualTo(vehicle.getPlateNumber());
                    assertThat(row.gpsAvailable()).isFalse();
                });
    }

    @Test
    void outsideApproachingRadiusIsNeitherVisibleNorQueued() {
        Fixture fixture = fixture("OUTSIDE");
        gps.receive(fixture.device(), fixAtDistance(fixture, 1.20));

        assertThat(approachingVehicleIds()).doesNotContain(fixture.vehicle().getId());
        assertThat(entries.existsByVehicleIdAndStatusIn(fixture.vehicle().getId(),
                List.of(TerminalQueueStatus.WAITING, TerminalQueueStatus.BOARDING))).isFalse();
    }

    @Test
    void exactlyOneKilometerIsApproachingWithoutQueuePosition() {
        Fixture fixture = fixture("ONE-KM");
        gps.receive(fixture.device(), fixAtDistance(fixture, 1.00));

        var row = approaching(fixture);
        assertThat(row.distanceKm()).isEqualTo(1.00);
        assertThat(entries.findAll()).noneMatch(entry -> entry.getVehicle().getId().equals(fixture.vehicle().getId()));
    }

    @Test
    void fiveHundredMetersIsApproachingOnly() {
        Fixture fixture = fixture("FIVE-HUNDRED");
        gps.receive(fixture.device(), fixAtDistance(fixture, 0.50));

        assertThat(approaching(fixture).distanceKm()).isEqualTo(0.50);
        assertThat(entries.findAll()).noneMatch(entry -> entry.getVehicle().getId().equals(fixture.vehicle().getId()));
    }

    @Test
    void oneHundredMetersIsApproachingOnly() {
        Fixture fixture = fixture("ONE-HUNDRED");
        gps.receive(fixture.device(), fixAtDistance(fixture, 0.10));

        assertThat(approaching(fixture).distanceKm()).isEqualTo(0.10);
        assertThat(entries.findAll()).noneMatch(entry -> entry.getVehicle().getId().equals(fixture.vehicle().getId()));
    }

    @Test
    void exactlyFiftyMetersCreatesWaitingEntryAndLeavesApproaching() {
        Fixture fixture = fixture("FIFTY");
        gps.receive(fixture.device(), fixAtDistance(fixture, 0.05));

        assertWaitingOnly(fixture);
    }

    @Test
    void thirtyMetersCreatesWaitingEntry() {
        Fixture fixture = fixture("THIRTY");
        gps.receive(fixture.device(), fixAtDistance(fixture, 0.03));

        assertWaitingOnly(fixture);
    }

    @Test
    void staleGpsDoesNotProduceApproachingOrPersistentEntry() {
        Fixture fixture = fixture("STALE-APP");
        saveLocation(fixture, 0.70, Instant.now().minusSeconds(46));

        assertThat(approachingVehicleIds()).doesNotContain(fixture.vehicle().getId());
        assertThat(entries.findAll()).noneMatch(entry -> entry.getVehicle().getId().equals(fixture.vehicle().getId()));
    }

    @Test
    void activeQueueVehicleNeverAppearsAsApproaching() {
        Fixture fixture = fixture("NO-DUPLICATE");
        queue.checkInFromGps(fixture.vehicle().getId(), GRAND_LAT, GRAND_LNG);
        saveLocation(fixture, 0.20, Instant.now());

        assertThat(approachingVehicleIds()).doesNotContain(fixture.vehicle().getId());
        assertThat(queue.getDashboard().grandTerminal().waiting())
                .anyMatch(row -> row.vehicleId().equals(fixture.vehicle().getId()));
    }

    @Test
    void approachingOrderIsNearestFirstWithoutAffectingFifo() {
        Fixture a = fixture("ORDER-A");
        Fixture b = fixture("ORDER-B");
        Fixture c = fixture("ORDER-C");
        gps.receive(a.device(), fixAtDistance(a, 0.80));
        gps.receive(b.device(), fixAtDistance(b, 0.30));
        gps.receive(c.device(), fixAtDistance(c, 0.60));

        Set<Long> ids = Set.of(a.vehicle().getId(), b.vehicle().getId(), c.vehicle().getId());
        assertThat(queue.getDashboard().grandTerminal().approaching().stream()
                .filter(row -> ids.contains(row.vehicleId())).map(row -> row.vehicleId()).toList())
                .containsExactly(b.vehicle().getId(), c.vehicle().getId(), a.vehicle().getId());

        TerminalQueueEntry first = saveEntry(a.vehicle(), TerminalCode.GRAND_TERMINAL, Instant.now().minusSeconds(10));
        saveEntry(b.vehicle(), TerminalCode.GRAND_TERMINAL, Instant.now());
        assertThat(queue.getDashboard().grandTerminal().waiting().stream()
                .filter(row -> Set.of(a.vehicle().getId(), b.vehicle().getId()).contains(row.vehicleId()))
                .map(row -> row.id()).toList()).startsWith(first.getId());
    }

    @Test
    void manualCheckInWorksWithoutGpsOrProximity() {
        Vehicle vehicle = vehicle("MANUAL-FAR");
        var row = queue.manualCheckIn(TerminalCode.SM_TERMINAL, vehicle.getId(), staff());

        assertThat(row.queueStatus()).isEqualTo(TerminalQueueStatus.WAITING);
        assertThat(row.checkInSource()).isEqualTo(QueueCheckInSource.STAFF);
    }

    @Test
    void activeQueueMembershipPersistsWhenLatestGpsIsFarAway() {
        Fixture fixture = fixture("FAR-WAITING");
        queue.checkInFromGps(fixture.vehicle().getId(), GRAND_LAT, GRAND_LNG);
        saveLocation(fixture, 1.20, Instant.now());

        assertThat(queue.getDashboard().grandTerminal().waiting())
                .anyMatch(row -> row.vehicleId().equals(fixture.vehicle().getId()));
        assertThat(entries.findByVehicleIdAndStatusIn(fixture.vehicle().getId(),
                List.of(TerminalQueueStatus.WAITING, TerminalQueueStatus.BOARDING))).isPresent();
    }

    private Object invokeBoarding(Long id, CountDownLatch start) throws InterruptedException {
        start.await();
        try { return queue.startBoarding(id); }
        catch (RuntimeException failure) { return failure; }
    }

    private Fixture fixture(String prefix) {
        Vehicle vehicle = vehicle(prefix);
        String suffix = UUID.randomUUID().toString();
        Device device = devices.saveAndFlush(Device.builder().deviceId("bus-" + suffix).deviceName("Queue GPS test")
                .deviceType(DeviceType.VEHICLE_TERMINAL).vehicleId(vehicle.getId())
                .plateNumber(vehicle.getPlateNumber()).tokenHash("synthetic-test-hash").status(DeviceStatus.ACTIVE).build());
        return new Fixture(vehicle, DevicePrincipal.from(device));
    }

    private Vehicle vehicle(String prefix) {
        String plate = (prefix + "-" + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();
        return vehicles.saveAndFlush(Vehicle.builder().plateNumber(plate).totalCapacity(50)
                .status(VehicleStatus.ACTIVE).route("SM Terminal to Grand Terminal").build());
    }

    private Admin staff() {
        String suffix = UUID.randomUUID().toString();
        return admins.saveAndFlush(Admin.builder().adminId(suffix.substring(0, 12)).username(suffix)
                .fullName("Queue Staff").password("synthetic-test-password").role(AdminRole.STAFF)
                .active(true).is2FaEnabled(false).build());
    }

    private TerminalQueueEntry saveEntry(Vehicle vehicle, TerminalCode terminal, Instant time) {
        return entries.saveAndFlush(TerminalQueueEntry.builder().terminal(terminal).vehicle(vehicle)
                .vehiclePlateNumber(vehicle.getPlateNumber()).status(TerminalQueueStatus.WAITING)
                .checkInSource(QueueCheckInSource.STAFF).checkedInAt(time).build());
    }

    private com.premier.staffqueue.response.ApproachingBusResponse approaching(Fixture fixture) {
        return queue.getDashboard().grandTerminal().approaching().stream()
                .filter(row -> row.vehicleId().equals(fixture.vehicle().getId())).findFirst().orElseThrow();
    }

    private List<Long> approachingVehicleIds() {
        var dashboard = queue.getDashboard();
        return java.util.stream.Stream.concat(dashboard.smTerminal().approaching().stream(),
                        dashboard.grandTerminal().approaching().stream())
                .map(row -> row.vehicleId()).toList();
    }

    private void assertWaitingOnly(Fixture fixture) {
        assertThat(queue.getDashboard().grandTerminal().waiting())
                .anyMatch(row -> row.vehicleId().equals(fixture.vehicle().getId()));
        assertThat(approachingVehicleIds()).doesNotContain(fixture.vehicle().getId());
        assertThat(entries.findByVehicleIdAndStatusIn(fixture.vehicle().getId(),
                List.of(TerminalQueueStatus.WAITING, TerminalQueueStatus.BOARDING))).isPresent();
    }

    private void saveLocation(Fixture fixture, double distanceKm, Instant capturedAt) {
        Device device = devices.findByDeviceId(fixture.device().deviceId()).orElseThrow();
        device.setGpsState("GPS_VALID");
        devices.saveAndFlush(device);
        locations.saveAndFlush(DriverLocation.builder().plateNumber(fixture.vehicle().getPlateNumber())
                .deviceId(fixture.device().deviceId()).latitude(latitudeNorthOfGrand(distanceKm)).longitude(GRAND_LNG)
                .capturedAt(capturedAt).receivedAt(Instant.now())
                .recordedAt(LocalDateTime.ofInstant(capturedAt, ZoneId.of("Asia/Manila"))).speed(10.0).build());
    }

    private GpsTelemetryRequest fixAtDistance(Fixture fixture, double distanceKm) {
        return fix(fixture, latitudeNorthOfGrand(distanceKm), GRAND_LNG);
    }

    private double latitudeNorthOfGrand(double distanceKm) {
        return GRAND_LAT + Math.toDegrees(distanceKm / 6371.0);
    }

    private GpsTelemetryRequest fix(Fixture fixture, double latitude, double longitude) {
        GpsTelemetryRequest request = new GpsTelemetryRequest();
        request.setPlateNumber(fixture.vehicle().getPlateNumber());
        request.setRequestNonce(UUID.randomUUID().toString());
        request.setRequestTimestamp(Instant.now().toString());
        request.setCapturedAt(Instant.now());
        request.setLatitude(latitude);
        request.setLongitude(longitude);
        request.setSpeed(1.0);
        request.setHeading(0.0);
        request.setFixValid(true);
        request.setFixType(3);
        request.setSatellites(8);
        request.setHdop(1.0);
        return request;
    }

    private record Fixture(Vehicle vehicle, DevicePrincipal device) {}
}
