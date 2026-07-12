package com.premier.staffcash.repository;

import com.premier.staffcash.model.StaffCashRemittance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface StaffCashRemittanceRepository extends JpaRepository<StaffCashRemittance, Long> {
    Optional<StaffCashRemittance> findByStaffIdAndCollectionDate(Long staffId, LocalDate collectionDate);
}
