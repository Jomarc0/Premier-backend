package com.premier.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FcmTokenRequest {

    @NotBlank(message = "FCM token is required")
    @jakarta.validation.constraints.Size(max = 512, message = "FCM token is too long")
    private String fcmToken;
}
