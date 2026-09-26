package com.premier.staffqueue.request;

import com.premier.staffqueue.model.TerminalCode;
import jakarta.validation.constraints.NotNull;

public record ManualQueueCheckInRequest(
        @NotNull TerminalCode terminal,
        @NotNull Long vehicleId
) {}
