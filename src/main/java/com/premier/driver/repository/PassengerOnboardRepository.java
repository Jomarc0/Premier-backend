package com.premier.driver.repository;

import com.premier.driver.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PassengerOnboardRepository
        extends JpaRepository<PassengerOnboard, Long> {

    List<PassengerOnboard> findByShiftIdAndStatus(
            Long shiftId, OnboardStatus status);

    List<PassengerOnboard> findByShiftId(Long shiftId);

    long countByShiftIdAndStatus(
            Long shiftId, OnboardStatus status);
}