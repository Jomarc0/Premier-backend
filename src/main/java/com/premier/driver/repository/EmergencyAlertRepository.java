package com.premier.driver.repository;

import com.premier.driver.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EmergencyAlertRepository
        extends JpaRepository<EmergencyAlert, Long> {

    List<EmergencyAlert> findByStatus(AlertStatus status);
    List<EmergencyAlert> findByDriverIdOrderByCreatedAtDesc(
            Long driverId);
}