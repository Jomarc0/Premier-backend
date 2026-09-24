package com.premier.trip.response;

import com.premier.trip.model.VehicleTrip;

import java.time.LocalDateTime;

public record VehicleTripResponse(Long id, Long vehicleId, String plateNumber, Long driverShiftId,
                                  String direction, String originTerminal, String destinationTerminal,
                                  String status, LocalDateTime startedAt, LocalDateTime endedAt) {
    public static VehicleTripResponse from(VehicleTrip trip) {
        return new VehicleTripResponse(trip.getId(), trip.getVehicle().getId(), trip.getVehiclePlateNumber(),
                trip.getDriverShift().getId(), trip.getDirection().name(), trip.getOriginTerminal(),
                trip.getDestinationTerminal(), trip.getStatus().name(), trip.getStartedAt(), trip.getEndedAt());
    }
}
