package com.premier.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BiometricDeviceRequest {
    @NotBlank
    @Size(max = 128)
    private String deviceId;
}
