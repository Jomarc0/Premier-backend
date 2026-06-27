package com.premier.repository;

import com.premier.model.TopUpRequest;
import com.premier.model.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TopUpRequestRepository
        extends JpaRepository<TopUpRequest, Long> {

    // processPayment
    Optional<TopUpRequest> findByReferenceNumber(
        String referenceNumber);

    Optional<TopUpRequest> findByReferenceNumberAndPassengerId(
        String referenceNumber, Long passengerId);

    //verifyPayment
    Optional<TopUpRequest> findByPaymongoLinkId(
        String linkId);

    // Get passenger top-up history
    List<TopUpRequest> findByPassengerIdOrderByCreatedAtDesc(
        Long passengerId);

    // Check pending payments
    List<TopUpRequest> findByPassengerIdAndStatus(
        Long passengerId, TransactionStatus status);
    
}
