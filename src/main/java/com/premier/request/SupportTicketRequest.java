package com.premier.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;   

@Data
public class SupportTicketRequest {

    @NotBlank(message = "Subject is required")
    private String subject;

    @NotBlank(message = "Category is required")
    private String category;

    @NotBlank(message = "Message is required")
    private String message;
}