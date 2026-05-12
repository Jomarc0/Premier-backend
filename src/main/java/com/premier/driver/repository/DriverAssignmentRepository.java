package com.premier.driver.repository;

import com.premier.driver.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DriverAssignmentRepository
        extends JpaRepository<DriverAssignment, Long> {

    List<DriverAssignment> findByStatus(AssignmentStatus status);

    Optional<DriverAssignment> findByDriverIdAndStatus(
            Long driverId, AssignmentStatus status);

    Optional<DriverAssignment> findByVehiclePlateNumberAndStatus(
            String plateNumber, AssignmentStatus status);
}