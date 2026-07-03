package com.premier.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.premier.model.Transaction;
import com.premier.model.TransactionType;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Page<Transaction> findByPassengerIdOrderByCreatedAtDesc(Long passengerId, Pageable pageable);
    List<Transaction> findTop5ByPassengerIdOrderByCreatedAtDesc(Long passengerId);
    Page<Transaction> findByPassengerIdAndTypeOrderByCreatedAtDesc(Long passengerId, TransactionType type, Pageable pageable);
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
