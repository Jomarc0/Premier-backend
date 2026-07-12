package com.premier.driver.repository;

import com.premier.driver.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface DriverShiftRepository
        extends JpaRepository<DriverShift, Long> {

    Optional<DriverShift> findByDriverIdAndStatus(
            Long driverId, ShiftStatus status);

    Optional<DriverShift> findByVehiclePlateNumberAndStatus(
            String plateNumber, ShiftStatus status);

    Optional<DriverShift> findTopByVehiclePlateNumberAndShiftStartLessThanEqualOrderByShiftStartDesc(
            String plateNumber, LocalDateTime capturedAt);

    List<DriverShift> findByDriverIdOrderByCreatedAtDesc(
            Long driverId);
    
    List<DriverShift> findByStatus(
    		ShiftStatus status);
}
