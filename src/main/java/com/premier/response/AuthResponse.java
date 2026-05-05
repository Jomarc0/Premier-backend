package com.premier.response;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String tempToken;
    private boolean require2FA;
    private boolean requireSetup;
    private String passengerName;
    private Long passengerId;
    private String userId;
}