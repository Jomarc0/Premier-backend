package com.premier.driver.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IssueDriverShiftCodeRequest(
        @NotBlank @Size(max = 20) String plateNumber,
        @NotBlank @Size(min = 10, max = 240) String reason) {}
