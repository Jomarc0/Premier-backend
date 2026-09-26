package com.premier.payment.repository;
import com.premier.payment.model.PaymentNotification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
public interface PaymentNotificationRepository extends JpaRepository<PaymentNotification, Long> {
    List<PaymentNotification> findTop25ByStatusAndDueAtBeforeOrderByDueAtAsc(String status, Instant now);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select n from PaymentNotification n where n.id = :id")
    Optional<PaymentNotification> lock(@Param("id") Long id);
    Page<PaymentNotification> findByPassengerIdOrderByCreatedAtDesc(Long passengerId, Pageable pageable);
    Optional<PaymentNotification> findByIdAndPassengerId(Long id, Long passengerId);
    long countByPassengerIdAndReadAtIsNull(Long passengerId);
    @Modifying
    @Query("update PaymentNotification n set n.readAt = :readAt where n.passengerId = :passengerId and n.readAt is null")
    int markAllRead(@Param("passengerId") Long passengerId, @Param("readAt") Instant readAt);
}
