package com.premier.staffqueue.response;

import java.time.LocalDateTime;
import java.util.List;

public record BusQueueDashboardResponse(
        LocalDateTime refreshedAt,
        List<BusQueueItemResponse> incomingToSmTerminal,
        List<BusQueueItemResponse> incomingToGrandTerminal
) {
}
