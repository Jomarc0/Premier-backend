package com.premier.response;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TotpSetupResponse {
    private String secret;
    private String qrCodeUrl;
    private String manualEntryKey;
    private boolean is2FaEnabled;  
}