package com.premier.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;

import com.premier.model.Transaction;
import com.premier.model.TransactionType;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Page<Transaction> findByPassengerIdOrderByCreatedAtDesc(Long passengerId, Pageable pageable);
    List<Transaction> findTop5ByPassengerIdOrderByCreatedAtDesc(Long passengerId);
    Page<Transaction> findByPassengerIdAndTypeOrderByCreatedAtDesc(Long passengerId, TransactionType type, Pageable pageable);
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
    Optional<Transaction> findByOfflineTransactionId(String offlineTransactionId);
    Optional<Transaction> findByReferenceNumberAndPassengerId(String referenceNumber, Long passengerId);

    @EntityGraph(attributePaths = {"passenger", "vehicle", "driverShift", "driverShift.vehicle"})
    List<Transaction> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime start, LocalDateTime end);

    @Query("""
            select count(distinct function('date', t.createdAt))
            from Transaction t
            where t.type in (com.premier.model.TransactionType.FARE_DEDUCTION,
                             com.premier.model.TransactionType.RIDE_FARE)
            """)
    long countDistinctFareOperatingDays();
}
