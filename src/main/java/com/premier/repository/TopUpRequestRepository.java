package com.premier.repository;

import com.premier.model.TopUpRequest;
import com.premier.model.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface TopUpRequestRepository
        extends JpaRepository<TopUpRequest, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from TopUpRequest t where t.id = :id")
    Optional<TopUpRequest> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);

    org.springframework.data.domain.Slice<TopUpRequest> findByPassengerIdAndStatusInOrderByCreatedAtDesc(
            Long passengerId, java.util.Collection<TransactionStatus> statuses, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<TopUpRequest> findByStatusAndPaymongoLinkIdIsNotNullOrderByCreatedAtAsc(
            TransactionStatus status, org.springframework.data.domain.Pageable pageable);
    org.springframework.data.domain.Page<TopUpRequest> findByStatusAndPaymongoLinkIdIsNotNullAndIdGreaterThanOrderByIdAsc(
            TransactionStatus status, Long afterId, org.springframework.data.domain.Pageable pageable);

    // processPayment
    Optional<TopUpRequest> findByReferenceNumber(
        String referenceNumber);

    Optional<TopUpRequest> findByReferenceNumberAndPassengerId(
        String referenceNumber, Long passengerId);

    Optional<TopUpRequest> findByIdempotencyKey(String idempotencyKey);

    //verifyPayment
    Optional<TopUpRequest> findByPaymongoLinkId(
        String linkId);

    // Get passenger top-up history
    List<TopUpRequest> findByPassengerIdOrderByCreatedAtDesc(
        Long passengerId);

    // Check pending payments
    List<TopUpRequest> findByPassengerIdAndStatus(
        Long passengerId, TransactionStatus status);

    // Find pending top-ups that have expired
    List<TopUpRequest> findByStatusAndExpiresAtBefore(
        TransactionStatus status, LocalDateTime now);

    // Legacy top-ups created before expiration feature (expires_at IS NULL)
    List<TopUpRequest> findByStatusAndExpiresAtIsNull(
        TransactionStatus status);
}
