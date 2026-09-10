package com.premier.driver.security;

public record DriverPrincipal(Long driverId, Long shiftId, Long vehicleId, String plateNumber, String driverName) {}
