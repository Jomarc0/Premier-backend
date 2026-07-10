package com.premier.payment.repository;

import com.premier.model.PaymentMethod;
import com.premier.payment.model.FarePaymentAttempt;
import com.premier.payment.model.FarePaymentAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface FarePaymentAttemptRepository extends JpaRepository<FarePaymentAttempt, Long> {
    List<FarePaymentAttempt> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    long countByStatusAndCreatedAtBetween(FarePaymentAttemptStatus status, LocalDateTime start, LocalDateTime end);

    long countByPaymentMethodAndCreatedAtBetween(PaymentMethod paymentMethod, LocalDateTime start, LocalDateTime end);
}
