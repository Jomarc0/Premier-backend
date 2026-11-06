package com.premier.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class FarePaymentResponse {
    private String cardNumber;
    private String rfidUid;
    private BigDecimal baseFare;
    private BigDecimal deductedFare;
    private String discountType;
    private BigDecimal remainingBalance;
    private BigDecimal balanceBefore;
    private String referenceNumber;
    private String source;
    private String plateNumber;

    private LocalDateTime timestamp;
}
