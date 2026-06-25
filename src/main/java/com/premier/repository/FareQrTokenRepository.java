package com.premier.repository;

import com.premier.model.FareQrToken;
import com.premier.model.FareQrTokenStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface FareQrTokenRepository extends JpaRepository<FareQrToken, Long> {
    Optional<FareQrToken> findByTokenHash(String tokenHash);

    long countByPassengerIdAndStatus(Long passengerId, FareQrTokenStatus status);

    List<FareQrToken> findByPassengerIdAndStatus(Long passengerId, FareQrTokenStatus status);
}
