package com.premier.response;

import com.premier.model.PassengerStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PassengerResponse {
    private Long id;
    private String userId;
    private String cardNumber;
    private String rfidUid;
    private String phoneNumber;
    private BigDecimal balance;
    private boolean twoFactorEnabled;  
    private PassengerStatus status;
    private LocalDateTime createdAt;

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private PassengerResponse r = new PassengerResponse();
        public Builder id(Long v) { r.id = v; return this; }
        public Builder userId(String v) { r.userId = v; return this; }
        public Builder cardNumber(String v) { r.cardNumber = v; return this; }
        public Builder rfidUid(String v) { r.rfidUid = v; return this; }
        public Builder phoneNumber(String v) { r.phoneNumber = v; return this; }
        public Builder balance(BigDecimal v) { r.balance = v; return this; }
        public Builder twoFactorEnabled(boolean v) { r.twoFactorEnabled = v; return this; }
        
        public Builder is2FaEnabled(boolean v) { 
            r.twoFactorEnabled = v; 
            return this; 
        }
        
        public Builder status(PassengerStatus v) { r.status = v; return this; }
        public Builder createdAt(LocalDateTime v) { r.createdAt = v; return this; }
        public PassengerResponse build() { return r; }
    }

    // Keep existing getters
    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getCardNumber() { return cardNumber; }
    public String getRfidUid() { return rfidUid; }
    public String getPhoneNumber() { return phoneNumber; }
    public BigDecimal getBalance() { return balance; }
    public boolean isTwoFactorEnabled() { return twoFactorEnabled; }
    public PassengerStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}