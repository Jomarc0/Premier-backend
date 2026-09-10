package com.premier.driver.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DriverLoginRequest(
        @NotBlank @Size(max = 20) String plateNumber,
        @NotBlank @Size(min = 6, max = 20) String shiftCode) {}
