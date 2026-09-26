package com.premier.staffqueue.response;

import com.premier.staffqueue.model.BusQueueStatus;
import com.premier.staffqueue.model.QueueCheckInSource;
import com.premier.staffqueue.model.TerminalCode;
import com.premier.staffqueue.model.TerminalQueueStatus;
import com.premier.driver.model.VehicleStatus;

public record BusQueueItemResponse(
        Long id,
        TerminalCode terminal,
        Long vehicleId,
        String plateNumber,
        VehicleStatus vehicleStatus,
        String routeDirection,
        Double distanceKm,
        Long etaMinutes,
        Integer queuePosition,
        BusQueueStatus status,
        TerminalQueueStatus queueStatus,
        QueueCheckInSource checkInSource,
        String statusLabel,
        java.time.Instant checkedInAt,
        java.time.Instant boardingAt,
        java.time.Instant departedAt,
        Double latitude,
        Double longitude,
        boolean gpsAvailable,
        java.time.Instant capturedAt
) {
}
