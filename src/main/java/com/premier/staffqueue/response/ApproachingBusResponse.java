package com.premier.staffqueue.response;

import com.premier.driver.model.VehicleStatus;
import com.premier.staffqueue.model.TerminalCode;

import java.time.Instant;

public record ApproachingBusResponse(
        TerminalCode terminal,
        Long vehicleId,
        String plateNumber,
        VehicleStatus vehicleStatus,
        String routeDirection,
        Double distanceKm,
        boolean gpsAvailable,
        Instant lastGpsAt
) {}
