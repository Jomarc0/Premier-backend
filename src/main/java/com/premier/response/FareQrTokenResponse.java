package com.premier.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class FareQrTokenResponse {
    private String token;
    private String payload;
    private Long passengerId;
    private String cardNumber;
    private long expiresInSeconds;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expiresAt;
}
