package com.premier.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
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

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime timestamp;
}
