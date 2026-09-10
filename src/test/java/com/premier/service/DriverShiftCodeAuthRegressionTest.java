package com.premier.service;

import com.premier.admin.model.*;
import com.premier.admin.repository.AdminRepository;
import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import com.premier.driver.request.*;
import com.premier.driver.service.DriverPortalService;
import com.premier.exception.ClientException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class DriverShiftCodeAuthRegressionTest {
    @Autowired DriverPortalService portal;
    @Autowired DriverRepository drivers;
    @Autowired VehicleRepository vehicles;
    @Autowired DriverAssignmentRepository assignments;
    @Autowired DriverShiftRepository shifts;
    @Autowired AdminRepository admins;

    @Test void plateAloneCannotStartShiftAndOneTimeCodeIsConsumed() {
        var admin = admin(); var driver = driver(); var vehicle = vehicle();
        assignments.saveAndFlush(DriverAssignment.builder().driver(driver).vehicle(vehicle).status(AssignmentStatus.ACTIVE).build());

        assertThatThrownBy(() -> portal.login(new DriverLoginRequest(vehicle.getPlateNumber(), "")))
                .isInstanceOf(RuntimeException.class);

        var issued = portal.issueCode(admin, new IssueDriverShiftCodeRequest(vehicle.getPlateNumber(), "dispatch ticket DS-10001")).getData();
        var login = portal.login(new DriverLoginRequest(vehicle.getPlateNumber(), issued.shiftCode())).getData();
        assertThat(login.driverId()).isEqualTo(driver.getId());
        assertThat(login.shiftId()).isNotNull();
        assertThat(login.token()).isNotBlank();

        assertThatThrownBy(() -> portal.login(new DriverLoginRequest(vehicle.getPlateNumber(), issued.shiftCode())))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("Invalid or expired shift code");
    }

    @Test void issuedCodeIsBoundToAssignedVehicleAndDriver() {
        var admin = admin(); var driver = driver(); var vehicle = vehicle(); var otherVehicle = vehicle();
        assignments.saveAndFlush(DriverAssignment.builder().driver(driver).vehicle(vehicle).status(AssignmentStatus.ACTIVE).build());
        var issued = portal.issueCode(admin, new IssueDriverShiftCodeRequest(vehicle.getPlateNumber(), "dispatch ticket DS-10002")).getData();

        assertThatThrownBy(() -> portal.login(new DriverLoginRequest(otherVehicle.getPlateNumber(), issued.shiftCode())))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("Invalid or expired shift code");
        assertThat(shifts.findByVehiclePlateNumberAndStatus(otherVehicle.getPlateNumber(), ShiftStatus.ACTIVE)).isEmpty();
    }

    private Driver driver() {
        return drivers.saveAndFlush(Driver.builder().fullName("Synthetic Driver").licenseNumber(UUID.randomUUID().toString())
                .phoneNumber("synthetic").status(DriverStatus.ACTIVE).build());
    }
    private Vehicle vehicle() {
        return vehicles.saveAndFlush(Vehicle.builder().plateNumber(UUID.randomUUID().toString().substring(0, 12).toUpperCase())
                .totalCapacity(20).route("SM Terminal to Grand Terminal").status(VehicleStatus.ACTIVE).build());
    }
    private Admin admin() {
        String unique = UUID.randomUUID().toString();
        return admins.saveAndFlush(Admin.builder().adminId(unique.substring(0,20)).username(unique)
                .fullName("Synthetic Admin").password("unused").role(AdminRole.SUPER_ADMIN).active(true).is2FaEnabled(true).build());
    }
}
