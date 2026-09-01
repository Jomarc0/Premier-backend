package com.premier.driver.repository;

import com.premier.driver.model.DriverLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository; 

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository  
public interface DriverLocationRepository extends JpaRepository<DriverLocation, Long> {
    
    // ALL THESE METHODS WORK PERFECTLY
    Optional<DriverLocation> findTopByPlateNumberOrderByRecordedAtDesc(String plateNumber);
    
    List<DriverLocation> findByShiftIdOrderByRecordedAtAsc(Long shiftId);

    List<DriverLocation> findByPlateNumberAndRecordedAtGreaterThanEqualOrderByRecordedAtAsc(
            String plateNumber,
            LocalDateTime recordedAt);
    
    @Query("""
        SELECT dl FROM DriverLocation dl
        WHERE dl.recordedAt = (
            SELECT MAX(dl2.recordedAt)
            FROM DriverLocation dl2
            WHERE dl2.plateNumber = dl.plateNumber
        )
        ORDER BY dl.plateNumber
        """)
    List<DriverLocation> findLatestPerPlate();

    @Query("""
        SELECT dl FROM DriverLocation dl
        WHERE dl.latitude BETWEEN -90.0 AND 90.0
          AND dl.longitude BETWEEN -180.0 AND 180.0
          AND dl.latitude <> 0.0
          AND dl.longitude <> 0.0
          AND dl.recordedAt = (
              SELECT MAX(dl2.recordedAt)
              FROM DriverLocation dl2
              WHERE dl2.plateNumber = dl.plateNumber
                AND dl2.latitude BETWEEN -90.0 AND 90.0
                AND dl2.longitude BETWEEN -180.0 AND 180.0
                AND dl2.latitude <> 0.0
                AND dl2.longitude <> 0.0
          )
        ORDER BY dl.plateNumber
        """)
    List<DriverLocation> findLatestValidPerPlate();

    List<DriverLocation> findByPlateNumberAndRecordedAtBetweenOrderByRecordedAtAsc(
            String plateNumber, LocalDateTime start, LocalDateTime end);
    
    @Query("""
        SELECT dl FROM DriverLocation dl
        WHERE dl.recordedAt >= :since
        ORDER BY dl.recordedAt DESC
        """)
    List<DriverLocation> findSince(@Param("since") LocalDateTime since);
    List<DriverLocation> findByRecordedAtBetween(LocalDateTime start, LocalDateTime end);
    long countByRecordedAtBefore(LocalDateTime cutoff);
    void deleteByRecordedAtBefore(LocalDateTime cutoff);
}
