package com.premier.support.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SupportTicketNotesRequest {
    @NotBlank(message = "Admin notes are required.")
    private String adminNotes;
}
