package com.premier.cardfreeze.repository;

import com.premier.cardfreeze.model.CardFreezeRequest;
import com.premier.cardfreeze.model.CardFreezeRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CardFreezeRequestRepository extends JpaRepository<CardFreezeRequest, Long> {
    List<CardFreezeRequest> findByPassengerIdOrderByCreatedAtDesc(Long passengerId);
    List<CardFreezeRequest> findAllByOrderByCreatedAtDesc();
    Optional<CardFreezeRequest> findFirstByPassengerIdAndRfidCardIdAndStatus(
            Long passengerId,
            Long rfidCardId,
            CardFreezeRequestStatus status);
}
