package com.premier.request;

import jakarta.validation.constraints.*;

public class RegisterRequest {

    @NotBlank(message = "Card number is required")
    private String cardNumber;

    @NotBlank(message = "RFID UID is required")
    private String rfidUid;

    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    public String getCardNumber() { return cardNumber; }
    public void setCardNumber(String v) { this.cardNumber = v; }
    public String getRfidUid() { return rfidUid; }
    public void setRfidUid(String v) { this.rfidUid = v; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String v) { this.phoneNumber = v; }
}