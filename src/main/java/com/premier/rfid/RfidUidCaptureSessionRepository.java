package com.premier.rfid;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;

public interface RfidUidCaptureSessionRepository extends JpaRepository<RfidUidCaptureSession, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RfidUidCaptureSession s where s.requestId = :requestId")
    Optional<RfidUidCaptureSession> findLockedByRequestId(@Param("requestId") String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RfidUidCaptureSession> findFirstByDeviceIdAndStatusOrderByCreatedAtAsc(String deviceId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RfidUidCaptureSession> findFirstByDeviceIdIsNullAndStatusOrderByCreatedAtAsc(String status);

    @Modifying
    @Query("update RfidUidCaptureSession s set s.status = 'EXPIRED' where s.status = 'WAITING' and s.expiresAt <= :now")
    int expireWaiting(@Param("now") Instant now);

    @Modifying
    @Query("delete from RfidUidCaptureSession s where s.status <> 'WAITING' and s.expiresAt < :cutoff")
    int deleteOldFinished(@Param("cutoff") Instant cutoff);
}
