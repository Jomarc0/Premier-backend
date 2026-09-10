package com.premier.payment.repository;
import com.premier.payment.model.PaymentIntentIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface PaymentIntentIdentityRepository extends JpaRepository<PaymentIntentIdentity, String> {
    Optional<PaymentIntentIdentity> findByOfflineId(String offlineId);
}
