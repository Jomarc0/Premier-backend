package com.premier.support.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LostCardReportRequest {
    @NotBlank(message = "Email address is required so we can send your support update.")
    @Email(message = "Enter a valid email address.")
    private String email;
}
