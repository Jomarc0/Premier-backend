package com.premier.rfid;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DeviceFareRequest {
    private String requestId;
    private String idempotencyKey;
    private String requestNonce;
    private String requestTimestamp;
    private String rfidUid;
    private String payload;
    private String mobileNfcToken;
    private String plateNumber;
    private Boolean offlineSync;
    private String offlineTransactionId;
    private String offlineCapturedAt;
    private BigDecimal fareAmount;
    private Double latitude;
    private Double longitude;
}
