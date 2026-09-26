package com.premier.response;

import lombok.Builder;
import lombok.Value;
import java.time.Instant;

@Value
@Builder
public class NotificationResponse {
    Long id;
    String title;
    String message;
    String type;
    String reference;
    boolean read;
    Instant createdAt;
}
