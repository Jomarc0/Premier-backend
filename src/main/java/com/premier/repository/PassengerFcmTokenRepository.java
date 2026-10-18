package com.premier.repository;

import com.premier.model.PassengerFcmToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

public interface PassengerFcmTokenRepository extends JpaRepository<PassengerFcmToken, Long> {
    Optional<PassengerFcmToken> findByFcmToken(String fcmToken);
    List<PassengerFcmToken> findByPassengerId(Long passengerId);
    List<PassengerFcmToken> findTop11ByPassengerIdOrderByUpdatedAtDesc(Long passengerId);

    @Query("select distinct t.fcmToken from PassengerFcmToken t order by t.fcmToken")
    Page<String> findDistinctFcmTokens(Pageable pageable);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from PassengerFcmToken t where t.passenger.id = :passengerId and t.fcmToken = :token")
    int deleteOwnedToken(@org.springframework.data.repository.query.Param("passengerId") Long passengerId,
                         @org.springframework.data.repository.query.Param("token") String token);
}
