package com.premier.device.service;
import com.premier.device.repository.DeviceRepository;
import com.premier.device.request.DeviceHeartbeatRequest;
import com.premier.device.security.DevicePrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
@Service @RequiredArgsConstructor
public class DeviceHealthService {
    private final DeviceRepository devices;
    private final DeviceService authentication;
    private final ObjectMapper mapper;
    @Transactional
    public void heartbeat(DevicePrincipal principal, DeviceHeartbeatRequest request) {
        authentication.lockPaymentDevice(principal);
        authentication.validateFreshNonce(principal,request.getRequestNonce(),request.getRequestTimestamp());
        var device = devices.findById(principal.id()).orElseThrow();
        Instant now = Instant.now();
        if (device.getHeartbeatAt() != null && device.getHeartbeatAt().isAfter(now.minusSeconds(30))) return;
        // TX-only printer cannot measure readiness; accept UNKNOWN regardless of client assertion.
        request.setPrinter(DeviceHeartbeatRequest.Peripheral.UNKNOWN);
        try { device.setHealthSnapshot(mapper.writeValueAsString(request)); }
        catch (Exception failure) { throw new IllegalArgumentException("Invalid health report."); }
        device.setHeartbeatAt(now); devices.save(device);
    }
}
