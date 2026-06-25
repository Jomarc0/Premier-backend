package com.premier.staffqueue.response;

import com.premier.staffqueue.model.BusQueueStatus;

public record BusQueueItemResponse(
        String plateNumber,
        String routeDirection,
        Double distanceKm,
        Long etaMinutes,
        Integer queuePosition,
        BusQueueStatus status,
        String statusLabel
) {
}
