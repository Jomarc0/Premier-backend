package com.premier.device.security;

import com.premier.device.model.Device;
import com.premier.device.model.DeviceType;

public record DevicePrincipal(
        Long id,
        String deviceId,
        DeviceType deviceType,
        Long vehicleId,
        String plateNumber) {

    public static DevicePrincipal from(Device device) {
        return new DevicePrincipal(
                device.getId(),
                device.getDeviceId(),
                device.getDeviceType(),
                device.getVehicleId(),
                device.getPlateNumber());
    }
}
