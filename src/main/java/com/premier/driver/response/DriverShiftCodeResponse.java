package com.premier.driver.response;

import java.time.LocalDateTime;

public record DriverShiftCodeResponse(
        Long assignmentId,
        Long driverId,
        String driverName,
        Long vehicleId,
        String plateNumber,
        String shiftCode,
        LocalDateTime expiresAt) {}
