package com.premier.support.request;

import com.premier.support.model.SupportTicketIssueType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PublicSupportTicketRequest {
    @NotBlank(message = "Email Address is required.")
    @Email(message = "Email Address must be valid.")
    private String email;

    @NotNull(message = "Issue Type is required.")
    private SupportTicketIssueType issueType;

    @NotBlank(message = "Reason / Description is required.")
    @Size(max = 2000, message = "Reason / Description is too long.")
    private String reason;
}
