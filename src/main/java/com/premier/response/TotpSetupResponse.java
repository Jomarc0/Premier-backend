package com.premier.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TotpSetupResponse {
    private String secret;
    private String qrCodeUrl;
    private String manualEntryKey;

    @JsonProperty("is2FaEnabled")
    private boolean is2FaEnabled;  
}
