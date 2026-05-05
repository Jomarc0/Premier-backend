package com.premier.response;

import lombok.*;
import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TopUpResponse {
    private Long topUpId;
    private BigDecimal amount;
    private String checkoutUrl;
    private String referenceNumber;
    private String status;
}