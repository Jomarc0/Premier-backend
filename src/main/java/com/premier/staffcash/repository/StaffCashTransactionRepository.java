package com.premier.staffcash.repository;

import com.premier.staffcash.model.StaffCashTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StaffCashTransactionRepository extends JpaRepository<StaffCashTransaction, Long> {
    Optional<StaffCashTransaction> findByIdempotencyKey(String idempotencyKey);
    Optional<StaffCashTransaction> findByOfflineTransactionId(String offlineTransactionId);
    @EntityGraph(attributePaths = {"staff", "vehicle", "driverShift", "trip"})
    @org.springframework.data.jpa.repository.Query("select t from StaffCashTransaction t where t.staff.id = :staffId and coalesce(t.offlineCapturedAt,t.createdAt) >= :from and coalesce(t.offlineCapturedAt,t.createdAt) < :to order by t.createdAt desc")
    List<StaffCashTransaction> findByStaffIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long staffId, LocalDateTime from, LocalDateTime to);
    @EntityGraph(attributePaths = {"staff", "vehicle", "driverShift", "trip"})
    @org.springframework.data.jpa.repository.Query("select t from StaffCashTransaction t where coalesce(t.offlineCapturedAt,t.createdAt) >= :from and coalesce(t.offlineCapturedAt,t.createdAt) < :to order by t.createdAt desc")
    List<StaffCashTransaction> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime from, LocalDateTime to);
}
