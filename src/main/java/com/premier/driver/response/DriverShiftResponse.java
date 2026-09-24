package com.premier.driver.response;

import java.time.LocalDateTime;
import java.util.List;

public record DriverShiftResponse(
        Long shiftId,
        Long driverId,
        String driverName,
        Long vehicleId,
        String plateNumber,
        String route,
        Integer totalCapacity,
        Integer passengersOnboard,
        Integer availableSeats,
        Integer passengersServed,
        LocalDateTime shiftStart,
        com.premier.trip.response.VehicleTripResponse activeTrip,
        List<OnboardPassenger> onboardPassengers) {
    public record OnboardPassenger(Long onboardId, String userId, String dropOff, java.math.BigDecimal fare, Integer passengerCount) {}
}
