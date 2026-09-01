package com.premier.repository;

import com.premier.model.BiometricRefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface BiometricRefreshTokenRepository extends JpaRepository<BiometricRefreshToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from BiometricRefreshToken t join fetch t.passenger where t.tokenHash = :tokenHash")
    Optional<BiometricRefreshToken> findLockedByTokenHash(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("update BiometricRefreshToken t set t.revokedAt = :revokedAt " +
            "where t.passenger.id = :passengerId and t.deviceId = :deviceId and t.revokedAt is null")
    int revokeActiveForDevice(@Param("passengerId") Long passengerId,
                              @Param("deviceId") String deviceId,
                              @Param("revokedAt") Instant revokedAt);
}
