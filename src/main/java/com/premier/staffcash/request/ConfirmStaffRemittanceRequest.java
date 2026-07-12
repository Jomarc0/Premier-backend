package com.premier.staffcash.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ConfirmStaffRemittanceRequest {
    @NotNull private LocalDate date;
    @NotNull @DecimalMin("0.00") private BigDecimal actualCashReceived;
}
