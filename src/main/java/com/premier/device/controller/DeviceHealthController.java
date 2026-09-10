package com.premier.device.controller;
import com.premier.device.request.DeviceHeartbeatRequest;
import com.premier.device.security.DeviceContext;
import com.premier.device.service.DeviceHealthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequiredArgsConstructor
public class DeviceHealthController {
    private final DeviceHealthService service;
    private final com.premier.device.repository.GpsObservationRepository observations;
    @PostMapping("/api/rfid/heartbeat")
    public Map<String,String> report(@Valid @RequestBody DeviceHeartbeatRequest request) {
        service.heartbeat(DeviceContext.get(), request); return Map.of("status", "ACCEPTED");
    }
    @GetMapping("/api/admin/devices/{deviceId}/gps-observations")
    public Object observations(@PathVariable String deviceId) {
        if (deviceId.length() > 80) throw new IllegalArgumentException("Invalid device ID.");
        return observations.findTop100ByDeviceIdOrderByReceivedAtDesc(deviceId);
    }
}
