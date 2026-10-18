package com.premier.config;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface FareConfigurationRepository extends JpaRepository<FareConfiguration, Long> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select f from FareConfiguration f where f.id=1")
    Optional<FareConfiguration> findActiveForFareCalculation();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FareConfiguration f where f.id=1")
    Optional<FareConfiguration> findActiveForUpdate();
}
