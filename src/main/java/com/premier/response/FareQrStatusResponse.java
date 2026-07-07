package com.premier.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class FareQrStatusResponse {
    private String status;
    private long expiresInSeconds;
    private LocalDateTime expiresAt;
    private FarePaymentResponse payment;
}
