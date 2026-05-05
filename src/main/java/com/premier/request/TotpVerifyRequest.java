package com.premier.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TotpVerifyRequest {
    @NotBlank
    private String tempToken;

    @NotBlank
    private String totpCode;
}