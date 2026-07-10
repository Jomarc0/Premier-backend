package com.premier.support.request;

import com.premier.support.model.SupportTicketIssueType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PublicSupportTicketRequest {
    @NotBlank(message = "Card Number is required.")
    private String cardNumber;

    @NotBlank(message = "Email Address is required.")
    @Email(message = "Email Address must be valid.")
    private String email;

    @NotNull(message = "Issue Type is required.")
    private SupportTicketIssueType issueType;

    @NotBlank(message = "Reason / Description is required.")
    private String reason;
}
