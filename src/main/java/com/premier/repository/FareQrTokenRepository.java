package com.premier.repository;

import com.premier.model.FareQrToken;
import com.premier.model.FareQrTokenStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface FareQrTokenRepository extends JpaRepository<FareQrToken, Long> {
    Optional<FareQrToken> findByTokenHash(String tokenHash);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from FareQrToken t where t.tokenHash = :hash")
    Optional<FareQrToken> findLockedByTokenHash(@org.springframework.data.repository.query.Param("hash") String hash);
    @org.springframework.data.jpa.repository.Query("select t.passenger.id from FareQrToken t where t.tokenHash = :hash")
    Optional<Long> findPassengerIdByTokenHash(@org.springframework.data.repository.query.Param("hash") String hash);

    long countByPassengerIdAndStatus(Long passengerId, FareQrTokenStatus status);

    List<FareQrToken> findByPassengerIdAndStatus(Long passengerId, FareQrTokenStatus status);
}
