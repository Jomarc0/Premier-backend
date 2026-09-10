package com.premier.device.request;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class DeviceHeartbeatRequest {
    @NotBlank @Size(max=120) private String requestNonce;
    @NotBlank @Size(max=64) private String requestTimestamp;
    public enum Peripheral { READY, UNAVAILABLE, UNKNOWN }
    @NotBlank @Size(max = 32) private String firmwareVersion;
    @Size(max = 80) private String buildVersion;
    @Min(-127) @Max(0) private Integer rssi;
    @Min(0) private Long uptimeSeconds;
    @Min(0) private Long minimumFreeHeap;
    @Min(0) private Long rebootCount;
    @Size(max = 60) private String rebootReason;
    @Min(0) @Max(10000) private Integer queueDepth;
    @Min(0) private Long oldestQueueAgeSeconds;
    @Min(0) private Long gpsAgeSeconds;
    @Min(0) @Max(100) private Integer satellites;
    @Min(0) private Long journalWrites;
    @Size(max = 60) private String lastSafeError;
    private Peripheral nfc = Peripheral.UNKNOWN;
    private Peripheral scanner = Peripheral.UNKNOWN;
    private Peripheral printer = Peripheral.UNKNOWN;
}
