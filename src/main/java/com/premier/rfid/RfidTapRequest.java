package com.premier.rfid;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RfidTapRequest {

    @NotBlank(message = "RFID UID must not be blank")
    @Size(min = 4, max = 20, message = "Invalid RFID UID length")
    @Pattern(regexp = "^[A-Fa-f0-9]+$", message = "RFID UID must be hexadecimal")
    private String rfidUid;
}