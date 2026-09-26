package com.premier.staffqueue.response;

import com.premier.staffqueue.model.TerminalCode;
import java.util.List;

public record TerminalQueueResponse(
        TerminalCode terminal,
        BusQueueItemResponse boarding,
        List<BusQueueItemResponse> waiting,
        List<ApproachingBusResponse> approaching
) {
    public TerminalQueueResponse(TerminalCode terminal, BusQueueItemResponse boarding,
                                 List<BusQueueItemResponse> waiting) {
        this(terminal, boarding, waiting, List.of());
    }
}
