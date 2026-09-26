package com.premier.service;

import com.premier.admin.model.Admin;
import com.premier.admin.model.AdminRole;
import com.premier.admin.repository.AdminRepository;
import com.premier.device.model.Device;
import com.premier.device.model.DeviceType;
import com.premier.device.repository.DeviceRepository;
import com.premier.device.security.DevicePrincipal;
import com.premier.driver.model.Driver;
import com.premier.driver.model.DriverShift;
import com.premier.driver.model.DriverStatus;
import com.premier.driver.model.Vehicle;
import com.premier.driver.model.VehicleStatus;
import com.premier.driver.repository.DriverRepository;
import com.premier.driver.repository.DriverShiftRepository;
import com.premier.driver.repository.VehicleRepository;
import com.premier.rfid.DeviceFareRequest;
import com.premier.staffcash.model.StaffCashCard;
import com.premier.staffcash.model.StaffCashCardPurpose;
import com.premier.staffcash.model.StaffCashCardStatus;
import com.premier.staffcash.model.StaffCashTransaction;
import com.premier.staffcash.repository.StaffCashCardRepository;
import com.premier.staffcash.repository.StaffCashTransactionRepository;
import com.premier.staffcash.service.AdminStaffCashService;
import com.premier.staffcash.service.StaffCashFareService;
import com.premier.trip.model.TripDirection;
import com.premier.trip.model.TripStatus;
import com.premier.trip.model.VehicleTrip;
import com.premier.trip.repository.VehicleTripRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShiftIndependentStaffCashRegressionTest {

    @Autowired StaffCashFareService fares;
    @Autowired AdminStaffCashService reports;
    @Autowired StaffCashTransactionRepository transactions;
    @Autowired StaffCashCardRepository cards;
    @Autowired AdminRepository admins;
    @Autowired VehicleRepository vehicles;
    @Autowired DeviceRepository devices;
    @Autowired DriverRepository drivers;
    @Autowired DriverShiftRepository shifts;
    @Autowired VehicleTripRepository trips;

    @Test
    void staffCashSucceedsWithoutDriverShiftOrTripAndAdminReportLoads() {
        Fixture fixture = fixture();
        DeviceFareRequest request = request(fixture);

        var response = fares.process(request, fixture.device()).getData();
        StaffCashTransaction stored = transactions.findAll().stream()
                .filter(row -> response.getReferenceNumber().equals(row.getReferenceNumber()))
                .findFirst().orElseThrow();

        assertThat(stored.getVehicle().getId()).isEqualTo(fixture.vehicle().getId());
        assertThat(stored.getVehiclePlateNumber()).isEqualTo(fixture.vehicle().getPlateNumber());
        assertThat(stored.getDriverShift()).isNull();
        assertThat(stored.getTrip()).isNull();
        assertThat(reports.detail(fixture.staff().getId(), LocalDate.now()).getData().transactions())
                .anySatisfy(row -> {
                    assertThat(row.referenceNumber()).isEqualTo(response.getReferenceNumber());
                    assertThat(row.plateNumber()).isEqualTo(fixture.vehicle().getPlateNumber());
                    assertThat(row.driverShiftId()).isNull();
                });
    }

    @Test
    void historicalShiftAndTripReferencesRemainReadable() {
        Fixture fixture = fixture();
        Driver driver = drivers.saveAndFlush(Driver.builder().fullName("Historical Driver")
                .licenseNumber("DL-" + UUID.randomUUID()).phoneNumber("09000000000")
                .status(DriverStatus.INACTIVE).build());
        DriverShift shift = shifts.saveAndFlush(DriverShift.builder().driver(driver).vehicle(fixture.vehicle()).build());
        VehicleTrip trip = trips.saveAndFlush(VehicleTrip.builder().vehicle(fixture.vehicle())
                .vehiclePlateNumber(fixture.vehicle().getPlateNumber()).driverShift(shift)
                .direction(TripDirection.SM_TO_GRAND).originTerminal("SM Terminal")
                .destinationTerminal("Grand Terminal").startedAt(LocalDateTime.now().minusMinutes(20))
                .endedAt(LocalDateTime.now().minusMinutes(5)).status(TripStatus.COMPLETED).build());
        StaffCashTransaction historical = transactions.saveAndFlush(StaffCashTransaction.builder()
                .staff(fixture.staff()).operationCard(fixture.card()).vehicle(fixture.vehicle())
                .vehiclePlateNumber(fixture.vehicle().getPlateNumber()).driverShift(shift).trip(trip)
                .deviceId(fixture.device().deviceId()).fareCategory(StaffCashCardPurpose.REGULAR_CASH)
                .baseFare(new BigDecimal("60.00")).discountAmount(BigDecimal.ZERO)
                .finalFare(new BigDecimal("60.00")).referenceNumber("CASH-" + UUID.randomUUID().toString().replace("-", ""))
                .idempotencyKey(UUID.randomUUID().toString()).build());

        var report = reports.detail(fixture.staff().getId(), LocalDate.now()).getData();

        assertThat(report.transactions()).anySatisfy(row -> {
            assertThat(row.referenceNumber()).isEqualTo(historical.getReferenceNumber());
            assertThat(row.driverShiftId()).isEqualTo(shift.getId());
        });
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Admin verifier = admins.saveAndFlush(Admin.builder().adminId(("A" + suffix).substring(0, 20))
                .username("admin-" + suffix).fullName("Verifier").password("unused")
                .role(AdminRole.SUPER_ADMIN).active(true).is2FaEnabled(true).build());
        Admin staff = admins.saveAndFlush(Admin.builder().adminId(("S" + suffix).substring(0, 20))
                .username("staff-" + suffix).fullName("Terminal Staff").password("unused")
                .role(AdminRole.STAFF).active(true).is2FaEnabled(true).build());
        Vehicle vehicle = vehicles.saveAndFlush(Vehicle.builder().plateNumber(("CASH-" + suffix.substring(0, 8)).toUpperCase())
                .totalCapacity(40).route("SM Terminal to Grand Terminal").status(VehicleStatus.ACTIVE).build());
        Device deviceRow = devices.saveAndFlush(Device.builder().deviceId("bus-" + suffix)
                .deviceName("Cash terminal").deviceType(DeviceType.VEHICLE_TERMINAL)
                .vehicleId(vehicle.getId()).plateNumber(vehicle.getPlateNumber()).tokenHash("test-only").build());
        StaffCashCard card = cards.saveAndFlush(StaffCashCard.builder().rfidUid(suffix.substring(0, 14).toUpperCase())
                .staff(staff).purpose(StaffCashCardPurpose.REGULAR_CASH).status(StaffCashCardStatus.ACTIVE)
                .registeredBy(verifier).build());
        return new Fixture(staff, vehicle, DevicePrincipal.from(deviceRow), card);
    }

    private DeviceFareRequest request(Fixture fixture) {
        DeviceFareRequest request = new DeviceFareRequest();
        request.setRfidUid(fixture.card().getRfidUid());
        request.setPlateNumber(fixture.vehicle().getPlateNumber());
        request.setIdempotencyKey(UUID.randomUUID().toString());
        request.setRequestNonce(UUID.randomUUID().toString());
        request.setRequestTimestamp(Instant.now().toString());
        return request;
    }

    private record Fixture(Admin staff, Vehicle vehicle, DevicePrincipal device, StaffCashCard card) {}
}
