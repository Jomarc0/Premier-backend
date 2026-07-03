package com.premier.device.repository;

import com.premier.device.model.Device;
import com.premier.device.model.DeviceNonce;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceNonceRepository extends JpaRepository<DeviceNonce, Long> {
    boolean existsByDeviceAndNonce(Device device, String nonce);
}
