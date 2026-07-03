package com.premier.device.service;

import com.premier.device.model.*;
import com.premier.device.repository.DeviceNonceRepository;
import com.premier.device.repository.DeviceRepository;
import com.premier.device.request.AdminDeviceRequest;
import com.premier.device.response.DeviceProvisioningResponse;
import com.premier.device.security.DevicePrincipal;
import com.premier.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.*;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private final DeviceRepository deviceRepository;
    private final DeviceNonceRepository nonceRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public DevicePrincipal authenticate(String deviceId, String rawToken) {
        if (isBlank(deviceId) || isBlank(rawToken)) {
            throw new SecurityException("Device authentication required.");
        }

        Device device = deviceRepository.findByDeviceId(deviceId.trim())
                .orElseThrow(() -> new SecurityException("Invalid device credentials."));

        if (!device.isActive()) {
            throw new SecurityException("Device is inactive or revoked.");
        }

        if (!passwordEncoder.matches(rawToken, device.getTokenHash())) {
            throw new SecurityException("Invalid device credentials.");
        }

        device.setLastSeenAt(LocalDateTime.now());
        deviceRepository.save(device);
        return DevicePrincipal.from(device);
    }

    @Transactional
    public void validateFreshNonce(DevicePrincipal principal, String nonce, String timestamp) {
        if (principal == null || isBlank(nonce) || isBlank(timestamp)) {
            throw new SecurityException("Device nonce and timestamp are required.");
        }

        Instant requestedAt;
        try {
            requestedAt = Instant.parse(timestamp.trim());
        } catch (Exception e) {
            throw new SecurityException("Invalid device timestamp.");
        }

        Instant now = Instant.now();
        if (requestedAt.isBefore(now.minus(MAX_CLOCK_SKEW))
                || requestedAt.isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw new SecurityException("Expired device timestamp.");
        }

        Device device = deviceRepository.findById(principal.id())
                .orElseThrow(() -> new SecurityException("Device not found."));

        if (nonceRepository.existsByDeviceAndNonce(device, nonce.trim())) {
            throw new SecurityException("Duplicate device nonce.");
        }

        nonceRepository.save(DeviceNonce.builder()
                .device(device)
                .nonce(nonce.trim())
                .requestTimestamp(LocalDateTime.ofInstant(requestedAt, ZoneOffset.UTC))
                .build());
    }

    public void requirePlateAssignment(DevicePrincipal principal, String plateNumber) {
        if (principal == null) {
            throw new SecurityException("Device authentication required.");
        }
        if (isBlank(plateNumber)) {
            throw new SecurityException("Plate number is required.");
        }
        String assignedPlate = normalizePlate(principal.plateNumber());
        String submittedPlate = normalizePlate(plateNumber);
        if (isBlank(assignedPlate) || !assignedPlate.equals(submittedPlate)) {
            throw new SecurityException("Device is not assigned to this vehicle.");
        }
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<DeviceProvisioningResponse>> listDevices() {
        return ApiResponse.success("Devices fetched.",
                deviceRepository.findAll().stream().map(this::toResponse).toList());
    }

    @Transactional
    public ApiResponse<DeviceProvisioningResponse> registerDevice(AdminDeviceRequest request) {
        if (deviceRepository.existsByDeviceId(request.getDeviceId().trim())) {
            throw new RuntimeException("Device ID already exists.");
        }
        String rawToken = generateToken();
        Device device = Device.builder()
                .deviceId(request.getDeviceId().trim())
                .deviceName(request.getDeviceName().trim())
                .deviceType(request.getDeviceType())
                .vehicleId(request.getVehicleId())
                .plateNumber(normalizePlate(request.getPlateNumber()))
                .tokenHash(passwordEncoder.encode(rawToken))
                .status(request.getStatus() != null ? request.getStatus() : DeviceStatus.ACTIVE)
                .build();
        deviceRepository.save(device);
        DeviceProvisioningResponse response = toResponse(device);
        response.setOneTimeDeviceToken(rawToken);
        return ApiResponse.success("Device registered. Store the token now; it will not be shown again.", response);
    }

    @Transactional
    public ApiResponse<DeviceProvisioningResponse> assignDevice(Long id, AdminDeviceRequest request) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Device not found."));
        if (!isBlank(request.getDeviceName())) device.setDeviceName(request.getDeviceName().trim());
        if (request.getDeviceType() != null) device.setDeviceType(request.getDeviceType());
        device.setVehicleId(request.getVehicleId());
        device.setPlateNumber(normalizePlate(request.getPlateNumber()));
        if (request.getStatus() != null) device.setStatus(request.getStatus());
        deviceRepository.save(device);
        return ApiResponse.success("Device updated.", toResponse(device));
    }

    @Transactional
    public ApiResponse<DeviceProvisioningResponse> rotateToken(Long id) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Device not found."));
        String rawToken = generateToken();
        device.setTokenHash(passwordEncoder.encode(rawToken));
        device.setStatus(DeviceStatus.ACTIVE);
        device.setRevokedAt(null);
        deviceRepository.save(device);
        DeviceProvisioningResponse response = toResponse(device);
        response.setOneTimeDeviceToken(rawToken);
        return ApiResponse.success("Device token rotated. Store the token now; it will not be shown again.", response);
    }

    @Transactional
    public ApiResponse<DeviceProvisioningResponse> deactivate(Long id) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Device not found."));
        device.setStatus(DeviceStatus.INACTIVE);
        deviceRepository.save(device);
        return ApiResponse.success("Device deactivated.", toResponse(device));
    }

    @Transactional
    public ApiResponse<DeviceProvisioningResponse> revoke(Long id) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Device not found."));
        device.setStatus(DeviceStatus.REVOKED);
        device.setRevokedAt(LocalDateTime.now());
        deviceRepository.save(device);
        return ApiResponse.success("Device revoked.", toResponse(device));
    }

    private DeviceProvisioningResponse toResponse(Device device) {
        return DeviceProvisioningResponse.builder()
                .id(device.getId())
                .deviceId(device.getDeviceId())
                .deviceName(device.getDeviceName())
                .deviceType(device.getDeviceType())
                .vehicleId(device.getVehicleId())
                .plateNumber(device.getPlateNumber())
                .status(device.getStatus())
                .lastSeenAt(device.getLastSeenAt())
                .revokedAt(device.getRevokedAt())
                .build();
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizePlate(String plateNumber) {
        return isBlank(plateNumber) ? null : plateNumber.trim().toUpperCase();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
