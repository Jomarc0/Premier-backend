package com.premier.service;

import com.premier.admin.model.*;
import com.premier.admin.repository.AdminRepository;
import com.premier.admin.service.FleetAssignmentService;
import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import com.premier.exception.ClientException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class FleetAssignmentRegressionTest {
    @Autowired FleetAssignmentService fleet;
    @Autowired DriverRepository drivers;
    @Autowired VehicleRepository vehicles;
    @Autowired DriverShiftRepository shifts;
    @Autowired DriverAssignmentRepository assignments;
    @Autowired AdminRepository admins;
    private Driver driver() {
        return drivers.saveAndFlush(Driver.builder().fullName("Synthetic Driver").licenseNumber(UUID.randomUUID().toString())
                .phoneNumber("synthetic").status(DriverStatus.ACTIVE).build());
    }
    private Vehicle vehicle() {
        return vehicles.saveAndFlush(Vehicle.builder().plateNumber(UUID.randomUUID().toString())
                .totalCapacity(20).status(VehicleStatus.ACTIVE).build());
    }
    private Admin admin() {
        String unique=UUID.randomUUID().toString();
        return admins.saveAndFlush(Admin.builder().adminId(unique.substring(0,20)).username(unique)
                .fullName("Synthetic Admin").password("unused").role(AdminRole.SUPER_ADMIN).active(true).is2FaEnabled(true).build());
    }
    @Test void concurrentDriversCannotBothClaimVehicle() throws Exception {
        var admin=admin(); var one=driver(); var two=driver(); var vehicle=vehicle();
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            var attempts=new ArrayList<Future<Boolean>>();
            for(var driver:List.of(one,two)) attempts.add(pool.submit(() -> {
                start.await();
                try { fleet.assign(admin,driver.getId(),vehicle.getId());return true; }
                catch(ClientException rejected) { assertThat(rejected.getCode()).isEqualTo("FLEET_ASSIGNMENT_CONFLICT");return false; }
            }));
            start.countDown(); int successes=0;
            for(var result:attempts) if(result.get(15,TimeUnit.SECONDS))successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(assignments.findByVehicleIdAndStatus(vehicle.getId(),AssignmentStatus.ACTIVE)).isPresent();
        } finally { pool.shutdownNow(); }
    }
    @Test void activeShiftDoesNotControlOptionalFleetAssignment() {
        var admin=admin(); var driver=driver(); var vehicle=vehicle(); var alternate=vehicle();
        var assigned=fleet.assign(admin,driver.getId(),vehicle.getId()).getData();
        var shift=shifts.saveAndFlush(DriverShift.builder().driver(driver).vehicle(vehicle).status(ShiftStatus.ACTIVE).build());
        assertThat(fleet.unassign(admin,assigned.id()).getData()).isEqualTo("COMPLETED");
        assertThat(fleet.assign(admin,driver.getId(),alternate.getId()).getData().vehicleId()).isEqualTo(alternate.getId());
        assertThat(assignments.findById(assigned.id()).orElseThrow().getStatus()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(shifts.findById(shift.getId()).orElseThrow().getStatus()).isEqualTo(ShiftStatus.ACTIVE);
    }
}
