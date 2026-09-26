package com.premier.admin.service;

import com.premier.admin.model.Admin;
import com.premier.admin.model.ActivityLog;
import com.premier.admin.repository.ActivityLogRepository;
import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import com.premier.response.ApiResponse;
import com.premier.exception.ClientException;
import org.springframework.http.HttpStatus;
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
        Driver driver = driverRepository.findLockedById(driverId)
                .orElseThrow(() -> new ClientException(HttpStatus.NOT_FOUND, "DRIVER_NOT_FOUND", "Driver not found."));
        Vehicle vehicle = vehicleRepository.findLockedById(vehicleId)
                .orElseThrow(() -> new ClientException(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND", "Vehicle not found."));
        if (driver.getStatus() != DriverStatus.ACTIVE || vehicle.getStatus() != VehicleStatus.ACTIVE)
            throw conflict("Only an active driver and vehicle may be assigned.");

        assignmentRepository.findByDriverIdAndStatus(driverId, AssignmentStatus.ACTIVE)
                .filter(row -> !row.getVehicle().getId().equals(vehicleId))
                .ifPresent(row -> { throw conflict("Unassign this driver before reassignment."); });
        assignmentRepository.findByVehicleIdAndStatus(vehicleId, AssignmentStatus.ACTIVE)
                .filter(row -> !row.getDriver().getId().equals(driverId))
                .ifPresent(row -> { throw conflict("Unassign this vehicle before reassignment."); });

        var existing = assignmentRepository.findByDriverIdAndStatus(driverId, AssignmentStatus.ACTIVE);
        if (existing.isPresent()) return ApiResponse.success("Driver is already assigned to this vehicle.", AssignmentView.from(existing.get()));
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
        var parents = assignmentRepository.findParents(id)
                .orElseThrow(() -> new ClientException(HttpStatus.NOT_FOUND, "ASSIGNMENT_NOT_FOUND", "Fleet assignment not found."));
        driverRepository.findLockedById(parents.getDriverId()).orElseThrow();
        vehicleRepository.findLockedById(parents.getVehicleId()).orElseThrow();
        DriverAssignment assignment = assignmentRepository.findById(id).orElseThrow();
        if (assignment.getStatus() != AssignmentStatus.ACTIVE)
            return ApiResponse.success("Driver and vehicle already unassigned.", "COMPLETED");
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

    private ClientException conflict(String message) {
        return new ClientException(HttpStatus.CONFLICT, "FLEET_ASSIGNMENT_CONFLICT", message);
    }
    public record AssignmentView(Long id, Long driverId, String driverName,
                                 Long vehicleId, String plateNumber, LocalDateTime assignedAt) {
        static AssignmentView from(DriverAssignment row) {
            return new AssignmentView(row.getId(), row.getDriver().getId(), row.getDriver().getFullName(),
                    row.getVehicle().getId(), row.getVehicle().getPlateNumber(), row.getShiftStart());
        }
    }
}
