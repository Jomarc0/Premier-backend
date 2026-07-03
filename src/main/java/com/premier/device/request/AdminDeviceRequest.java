package com.premier.device.request;

import com.premier.device.model.DeviceStatus;
import com.premier.device.model.DeviceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AdminDeviceRequest {
    @NotBlank
    private String deviceId;

    @NotBlank
    private String deviceName;

    @NotNull
    private DeviceType deviceType;

    private Long vehicleId;
    private String plateNumber;
    private DeviceStatus status;
}
