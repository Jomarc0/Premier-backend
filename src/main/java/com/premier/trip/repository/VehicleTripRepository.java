package com.premier.trip.repository;

import com.premier.trip.model.VehicleTrip;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence access retained for historical fare and shift references. */
public interface VehicleTripRepository extends JpaRepository<VehicleTrip, Long> {
}
