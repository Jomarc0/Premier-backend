package com.premier.driver.repository;

import com.premier.driver.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DriverAssignmentRepository
        extends JpaRepository<DriverAssignment, Long> {

    interface Parents { Long getDriverId(); Long getVehicleId(); }
    @org.springframework.data.jpa.repository.Query("select a.driver.id as driverId, a.vehicle.id as vehicleId from DriverAssignment a where a.id = :id")
    Optional<Parents> findParents(@org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"driver", "vehicle"})
    List<DriverAssignment> findByStatus(AssignmentStatus status);

    Optional<DriverAssignment> findByDriverIdAndStatus(
            Long driverId, AssignmentStatus status);

    Optional<DriverAssignment> findByVehiclePlateNumberAndStatus(
            String plateNumber, AssignmentStatus status);

    Optional<DriverAssignment> findByVehicleIdAndStatus(
            Long vehicleId, AssignmentStatus status);
}
