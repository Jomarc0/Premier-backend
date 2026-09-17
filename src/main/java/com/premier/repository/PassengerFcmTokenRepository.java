package com.premier.repository;

import com.premier.model.PassengerFcmToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PassengerFcmTokenRepository extends JpaRepository<PassengerFcmToken, Long> {
    Optional<PassengerFcmToken> findByFcmToken(String fcmToken);
    List<PassengerFcmToken> findByPassengerId(Long passengerId);
    List<PassengerFcmToken> findTop11ByPassengerIdOrderByUpdatedAtDesc(Long passengerId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from PassengerFcmToken t where t.passenger.id = :passengerId and t.fcmToken = :token")
    int deleteOwnedToken(@org.springframework.data.repository.query.Param("passengerId") Long passengerId,
                         @org.springframework.data.repository.query.Param("token") String token);
}
