package com.premier.admin.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class AdminBalanceAdjustmentRequest {

    @NotNull(message = "Amount is required.")
    @DecimalMin(value = "1.00", message = "Amount must be at least 1.00.")
    @DecimalMax(value = "10000.00", message = "Amount must not exceed 10000.00.")
    private BigDecimal amount;

    @NotBlank(message = "Adjustment reason is required.")
    @Size(max = 240, message = "Reason must be 240 characters or fewer.")
    private String reason;
}
