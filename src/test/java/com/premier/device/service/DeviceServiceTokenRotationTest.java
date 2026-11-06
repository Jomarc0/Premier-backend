package com.premier.device.service;

import com.premier.device.model.Device;
import com.premier.device.model.DeviceStatus;
import com.premier.device.model.DeviceType;
import com.premier.device.repository.DeviceNonceRepository;
import com.premier.device.repository.DeviceRepository;
import com.premier.driver.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTokenRotationTest {
    @Mock DeviceRepository devices;
    @Mock DeviceNonceRepository nonces;
    @Mock VehicleRepository vehicles;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private DeviceService service;

    @BeforeEach
    void setUp() {
        service = new DeviceService(devices, nonces, vehicles, passwordEncoder);
    }

    @Test
    void rotationReturnsNewCredentialAndInvalidatesPreviouslyStoredToken() {
        Device device = Device.builder().id(3L).deviceId("bus-3").deviceName("NAD-1432 ESP32 Terminal")
                .deviceType(DeviceType.VEHICLE_TERMINAL).vehicleId(14L).plateNumber("NAD-1432")
                .tokenHash(passwordEncoder.encode("old-device-token"))
                .credentialVersion(7).status(DeviceStatus.ACTIVE).build();
        when(devices.findLockedById(3L)).thenReturn(Optional.of(device));

        var result = service.rotateToken(3L).getData();
        String newToken = result.getOneTimeDeviceToken();

        assertThat(newToken).isNotBlank().isNotEqualTo("old-device-token");
        assertThat(passwordEncoder.matches("old-device-token", device.getTokenHash())).isFalse();
        assertThat(passwordEncoder.matches(newToken, device.getTokenHash())).isTrue();
        assertThat(device.getCredentialVersion()).isEqualTo(8);
        assertThat(result.getDeviceId()).isEqualTo("bus-3");
        assertThat(result.getPlateNumber()).isEqualTo("NAD-1432");
        assertThat(result.getDeviceName()).isEqualTo("NAD-1432 ESP32 Terminal");
        verify(devices).save(device);
    }
}
