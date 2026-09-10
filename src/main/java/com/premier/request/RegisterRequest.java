package com.premier.request;

import jakarta.validation.constraints.*;

public class RegisterRequest {

    @NotBlank(message = "Card number is required")
    private String cardNumber;

    // Retained for old clients; enrollment does not collect unused phone data.
    @Size(max = 40)
    private String phoneNumber;

    public String getCardNumber() { return cardNumber; }
    public void setCardNumber(String v) { this.cardNumber = v; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String v) { this.phoneNumber = v; }
}
