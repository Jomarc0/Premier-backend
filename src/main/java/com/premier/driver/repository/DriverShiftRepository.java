package com.premier.driver.repository;

import com.premier.driver.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface DriverShiftRepository
        extends JpaRepository<DriverShift, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select shift from DriverShift shift where shift.id = :id")
    Optional<DriverShift> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);

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

    @EntityGraph(attributePaths = {"vehicle", "driver"})
    List<DriverShift> findByShiftStartBetween(LocalDateTime start, LocalDateTime end);
}
