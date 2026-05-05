package com.premier.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TicketReplyRequest {

    @NotBlank(message = "Message is required")
    private String message;
}