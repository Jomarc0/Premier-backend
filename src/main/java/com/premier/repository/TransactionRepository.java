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
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transaction t where t.id = :id")
    Optional<Transaction> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);
    Page<Transaction> findByPassengerIdOrderByCreatedAtDesc(Long passengerId, Pageable pageable);
    List<Transaction> findTop5ByPassengerIdOrderByCreatedAtDesc(Long passengerId);
    Page<Transaction> findByPassengerIdAndTypeOrderByCreatedAtDesc(Long passengerId, TransactionType type, Pageable pageable);
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
    Optional<Transaction> findByReversalOfId(Long reversalOfId);
    boolean existsByPassengerIdAndPaymentMethodAndStatusAndCreatedAtAfter(Long passengerId, com.premier.model.PaymentMethod method, com.premier.model.TransactionStatus status, LocalDateTime after);
    Optional<Transaction> findByOfflineTransactionId(String offlineTransactionId);
    Optional<Transaction> findByReferenceNumberAndPassengerId(String referenceNumber, Long passengerId);

    @EntityGraph(attributePaths = {"passenger", "vehicle", "driverShift", "trip"})
    @Query("select t from Transaction t")
    Page<Transaction> findAllForAdmin(Pageable pageable);

    @EntityGraph(attributePaths = {"passenger", "vehicle", "driverShift", "driverShift.vehicle", "trip"})
    @Query("select t from Transaction t where coalesce(t.offlineCapturedAt,t.createdAt) >= :start and coalesce(t.offlineCapturedAt,t.createdAt) <= :end order by coalesce(t.offlineCapturedAt,t.createdAt) desc")
    List<Transaction> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime start, LocalDateTime end);

    @Query("""
            select count(distinct function('date', coalesce(t.offlineCapturedAt, t.createdAt)))
            from Transaction t
            where t.type in (com.premier.model.TransactionType.FARE_DEDUCTION,
                             com.premier.model.TransactionType.RIDE_FARE)
            """)
    long countDistinctFareOperatingDays();
}
