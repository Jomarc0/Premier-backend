package com.premier.rfid;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class RfidTapResponse {

    private String cardNumber;

    private String rfidUid;

    private BigDecimal baseFare;

    private BigDecimal deductedFare;

    private String discountType;

    private BigDecimal remainingBalance;

    private String referenceNumber;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime timestamp;
}
