package com.premier.support.request;

import com.premier.support.model.SupportTicketStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SupportTicketStatusRequest {
    @NotNull(message = "Status is required.")
    private SupportTicketStatus status;
}
