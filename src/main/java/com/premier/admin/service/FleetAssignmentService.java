package com.premier.admin.service;

import com.premier.admin.model.Admin;
import com.premier.admin.model.ActivityLog;
import com.premier.admin.repository.ActivityLogRepository;
import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import com.premier.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FleetAssignmentService {
    private final DriverAssignmentRepository assignmentRepository;
    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final ActivityLogRepository activityLogRepository;

    @Transactional(readOnly = true)
    public ApiResponse<List<AssignmentView>> activeAssignments() {
        return ApiResponse.success("Active fleet assignments fetched.",
                assignmentRepository.findByStatus(AssignmentStatus.ACTIVE).stream()
                        .map(AssignmentView::from).toList());
    }

    @Transactional
    public ApiResponse<AssignmentView> assign(Admin admin, Long driverId, Long vehicleId) {
        Driver driver = driverRepository.findById(driverId)
                .orElseThrow(() -> new RuntimeException("Driver not found."));
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new RuntimeException("Vehicle not found."));

        assignmentRepository.findByDriverIdAndStatus(driverId, AssignmentStatus.ACTIVE)
                .filter(row -> !row.getVehicle().getId().equals(vehicleId))
                .ifPresent(this::complete);
        assignmentRepository.findByVehicleIdAndStatus(vehicleId, AssignmentStatus.ACTIVE)
                .filter(row -> !row.getDriver().getId().equals(driverId))
                .ifPresent(this::complete);

        DriverAssignment assignment = assignmentRepository
                .findByDriverIdAndStatus(driverId, AssignmentStatus.ACTIVE)
                .filter(row -> row.getVehicle().getId().equals(vehicleId))
                .orElseGet(() -> assignmentRepository.save(DriverAssignment.builder()
                        .driver(driver).vehicle(vehicle).status(AssignmentStatus.ACTIVE).build()));

        activityLogRepository.save(ActivityLog.builder().admin(admin)
                .action("ASSIGN_DRIVER_VEHICLE").targetType("DRIVER_ASSIGNMENT")
                .targetId(assignment.getId()).details("Assigned " + driver.getFullName()
                        + " to vehicle " + vehicle.getPlateNumber()).status("SUCCESS").build());
        return ApiResponse.success("Driver assigned to vehicle.", AssignmentView.from(assignment));
    }

    @Transactional
    public ApiResponse<String> unassign(Admin admin, Long id) {
        DriverAssignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Fleet assignment not found."));
        complete(assignment);
        activityLogRepository.save(ActivityLog.builder().admin(admin)
                .action("UNASSIGN_DRIVER_VEHICLE").targetType("DRIVER_ASSIGNMENT")
                .targetId(id).details("Unassigned " + assignment.getDriver().getFullName()
                        + " from vehicle " + assignment.getVehicle().getPlateNumber()).status("SUCCESS").build());
        return ApiResponse.success("Driver and vehicle unassigned.", "COMPLETED");
    }

    private void complete(DriverAssignment assignment) {
        assignment.setStatus(AssignmentStatus.COMPLETED);
        assignment.setShiftEnd(LocalDateTime.now());
        assignmentRepository.save(assignment);
    }

    public record AssignmentView(Long id, Long driverId, String driverName,
                                 Long vehicleId, String plateNumber, LocalDateTime assignedAt) {
        static AssignmentView from(DriverAssignment row) {
            return new AssignmentView(row.getId(), row.getDriver().getId(), row.getDriver().getFullName(),
                    row.getVehicle().getId(), row.getVehicle().getPlateNumber(), row.getShiftStart());
        }
    }
}
