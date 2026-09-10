package com.premier.device.repository;

import com.premier.device.model.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Device d set d.lastSeenAt = :now where d.id = :id and (d.lastSeenAt is null or d.lastSeenAt < :cutoff)")
    int touchLastSeen(@org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now,
            @org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from Device d where d.id = :id")
    Optional<Device> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);
    Optional<Device> findByDeviceId(String deviceId);
    java.util.List<Device> findByDeviceIdIn(java.util.Collection<String> deviceIds);
    boolean existsByDeviceId(String deviceId);
}
