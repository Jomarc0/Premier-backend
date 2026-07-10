package com.premier.support.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReplaceRfidUidRequest {
    @NotBlank(message = "New RFID UID is required.")
    private String newRfidUid;

    private String adminNotes;
}
