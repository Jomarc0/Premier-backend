package com.premier.staffcash.request;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
public record AdjustStaffRemittanceRequest(@NotNull LocalDate date,
        @NotNull @Digits(integer=8,fraction=2) BigDecimal amount,
        @NotBlank @Size(min=10,max=240) String reason,
        @NotBlank @Size(min=12,max=80) String requestId) {}
