package com.premier.repository;

import com.premier.model.PassengerFcmToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PassengerFcmTokenRepository extends JpaRepository<PassengerFcmToken, Long> {
    Optional<PassengerFcmToken> findByFcmToken(String fcmToken);
    List<PassengerFcmToken> findByPassengerId(Long passengerId);
}
