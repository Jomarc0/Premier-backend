package com.premier.payment.repository;
import com.premier.payment.model.PaymentNotification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
public interface PaymentNotificationRepository extends JpaRepository<PaymentNotification, Long> {
    List<PaymentNotification> findTop25ByStatusAndDueAtBeforeOrderByDueAtAsc(String status, Instant now);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select n from PaymentNotification n where n.id = :id")
    Optional<PaymentNotification> lock(@Param("id") Long id);
}
