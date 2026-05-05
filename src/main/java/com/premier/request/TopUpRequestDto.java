package com.premier.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class TopUpRequestDto {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "20.00",      
        message = "Minimum top-up is ₱20")
    @DecimalMax(value = "10000.00",
        message = "Maximum top-up is ₱10,000")
    private BigDecimal amount;
}