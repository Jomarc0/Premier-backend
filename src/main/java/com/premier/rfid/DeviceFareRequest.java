package com.premier.rfid;

import lombok.Data;

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
}
