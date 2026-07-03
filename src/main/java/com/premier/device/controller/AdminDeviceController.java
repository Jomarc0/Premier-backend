package com.premier.device.controller;

import com.premier.device.request.AdminDeviceRequest;
import com.premier.device.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/devices")
@RequiredArgsConstructor
public class AdminDeviceController {

    private final DeviceService deviceService;

    @GetMapping
    public ResponseEntity<?> listDevices() {
        return ResponseEntity.ok(deviceService.listDevices());
    }

    @PostMapping
    public ResponseEntity<?> registerDevice(@Valid @RequestBody AdminDeviceRequest request) {
        return ResponseEntity.ok(deviceService.registerDevice(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> assignDevice(@PathVariable Long id,
                                          @Valid @RequestBody AdminDeviceRequest request) {
        return ResponseEntity.ok(deviceService.assignDevice(id, request));
    }

    @PostMapping("/{id}/rotate-token")
    public ResponseEntity<?> rotateToken(@PathVariable Long id) {
        return ResponseEntity.ok(deviceService.rotateToken(id));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<?> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(deviceService.deactivate(id));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<?> revoke(@PathVariable Long id) {
        return ResponseEntity.ok(deviceService.revoke(id));
    }
}
