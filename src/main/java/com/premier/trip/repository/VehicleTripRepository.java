package com.premier.trip.repository;

import com.premier.trip.model.TripStatus;
import com.premier.trip.model.VehicleTrip;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VehicleTripRepository extends JpaRepository<VehicleTrip, Long> {
    @EntityGraph(attributePaths = {"vehicle", "driverShift"})
    Optional<VehicleTrip> findByVehicleIdAndStatus(Long vehicleId, TripStatus status);

    @EntityGraph(attributePaths = {"vehicle", "driverShift"})
    @Query("""
            select trip from VehicleTrip trip
            where trip.vehicle.id = :vehicleId
              and trip.startedAt <= :capturedAt
              and (trip.endedAt is null or trip.endedAt >= :capturedAt)
            order by trip.startedAt desc
            """)
    List<VehicleTrip> findCovering(@Param("vehicleId") Long vehicleId,
                                   @Param("capturedAt") LocalDateTime capturedAt,
                                   Pageable pageable);

    @EntityGraph(attributePaths = {"vehicle", "driverShift"})
    List<VehicleTrip> findByStartedAtBetween(LocalDateTime start, LocalDateTime end);
}
