package com.premier.staffcash.repository;

import com.premier.staffcash.model.StaffCashTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StaffCashTransactionRepository extends JpaRepository<StaffCashTransaction, Long> {
    Optional<StaffCashTransaction> findByIdempotencyKey(String idempotencyKey);
    Optional<StaffCashTransaction> findByOfflineTransactionId(String offlineTransactionId);
    List<StaffCashTransaction> findByStaffIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long staffId, LocalDateTime from, LocalDateTime to);
    List<StaffCashTransaction> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime from, LocalDateTime to);
}
