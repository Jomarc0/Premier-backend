package com.premier.driver.response;

public record DriverLoginResponse(
        String token,
        Long shiftId,
        Long driverId,
        String driverName,
        String plateNumber,
        String route,
        Integer totalCapacity) {}
