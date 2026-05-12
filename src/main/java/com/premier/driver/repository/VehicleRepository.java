package com.premier.driver.repository;

import com.premier.driver.model.Vehicle;
import com.premier.driver.model.VehicleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface VehicleRepository
        extends JpaRepository<Vehicle, Long> {

    Optional<Vehicle> findByPlateNumber(String plateNumber);
    boolean existsByPlateNumber(String plateNumber);
    List<Vehicle> findByStatus(VehicleStatus status);
}