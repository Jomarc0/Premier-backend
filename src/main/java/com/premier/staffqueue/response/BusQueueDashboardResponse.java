package com.premier.staffqueue.response;

import java.time.LocalDateTime;
import java.util.List;

public record BusQueueDashboardResponse(
        LocalDateTime refreshedAt,
        List<BusQueueItemResponse> incomingToSmTerminal,
        List<BusQueueItemResponse> incomingToGrandTerminal,
        TerminalQueueResponse smTerminal,
        TerminalQueueResponse grandTerminal
) {
    public BusQueueDashboardResponse(LocalDateTime refreshedAt,
                                     List<BusQueueItemResponse> incomingToSmTerminal,
                                     List<BusQueueItemResponse> incomingToGrandTerminal) {
        this(refreshedAt, incomingToSmTerminal, incomingToGrandTerminal,
                new TerminalQueueResponse(com.premier.staffqueue.model.TerminalCode.SM_TERMINAL, null, incomingToSmTerminal),
                new TerminalQueueResponse(com.premier.staffqueue.model.TerminalCode.GRAND_TERMINAL, null, incomingToGrandTerminal));
    }
}
