package com.premier.device.response;

import com.premier.device.model.DeviceStatus;
import com.premier.device.model.DeviceType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DeviceProvisioningResponse {
    private Long id;
    private String deviceId;
    private String deviceName;
    private DeviceType deviceType;
    private Long vehicleId;
    private String plateNumber;
    private DeviceStatus status;
    private LocalDateTime lastSeenAt;
    private LocalDateTime revokedAt;
    private String oneTimeDeviceToken;
}
