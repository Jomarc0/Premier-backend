package com.premier.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BiometricTokenRequest {
    @NotBlank
    @Size(max = 512)
    private String refreshToken;

    @NotBlank
    @Size(max = 128)
    private String deviceId;
}
