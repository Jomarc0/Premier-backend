package com.premier.admin.request;
import jakarta.validation.constraints.*;
public record FareReversalRequest(@NotBlank @Size(min = 10, max = 240) String reason) {}
