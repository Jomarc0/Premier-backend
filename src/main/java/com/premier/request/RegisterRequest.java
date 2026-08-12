package com.premier.request;

import jakarta.validation.constraints.*;

public class RegisterRequest {

    @NotBlank(message = "Card number is required")
    private String cardNumber;

    @NotBlank(message = "Activation code is required")
    @Size(min = 20, max = 64, message = "Invalid activation code")
    private String activationCode;

    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    public String getCardNumber() { return cardNumber; }
    public void setCardNumber(String v) { this.cardNumber = v; }
    public String getActivationCode() { return activationCode; }
    public void setActivationCode(String v) { this.activationCode = v; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String v) { this.phoneNumber = v; }
}
