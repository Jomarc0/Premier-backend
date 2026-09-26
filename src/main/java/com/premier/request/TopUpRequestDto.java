package com.premier.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class TopUpRequestDto {

    @NotNull(message = "Amount is required")
    @Digits(integer = 5, fraction = 2, message = "Use at most two decimal places")
    @DecimalMin(value = "20.00",      
        message = "Minimum top-up is ₱20")
    @DecimalMax(value = "10000.00",
        message = "Maximum top-up is ₱10,000")
    private BigDecimal amount;

    private String paymentMethod;

    @NotBlank(message = "Payment attempt ID is required")
    @Pattern(
        regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        message = "Payment attempt ID must be a UUID"
    )
    private String idempotencyKey;

}
