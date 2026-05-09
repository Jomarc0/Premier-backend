package com.premier.driver.repository;

import com.premier.driver.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DriverShiftRepository
        extends JpaRepository<DriverShift, Long> {

    Optional<DriverShift> findByDriverIdAndStatus(
            Long driverId, ShiftStatus status);

    Optional<DriverShift> findByVehiclePlateNumberAndStatus(
            String plateNumber, ShiftStatus status);

    List<DriverShift> findByDriverIdOrderByCreatedAtDesc(
            Long driverId);
    
    List<DriverShift> findByStatus(
    		ShiftStatus status);
}