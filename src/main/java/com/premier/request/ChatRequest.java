package com.premier.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank(message = "Message cannot be blank")
    @Size(max = 500, message = "Message too long (max 500 characters)")
    private String message;

    @Size(max = 100, message = "Session ID is too long")
    private String sessionId;
}
