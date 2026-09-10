package com.premier.device.request;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.time.Instant;
@Data
public class GpsTelemetryRequest {
    @NotBlank @Size(max = 40) private String plateNumber;
    @NotBlank @Size(max = 120) private String requestNonce;
    @NotBlank @Size(max = 64) private String requestTimestamp;
    private Instant capturedAt;
    private Double latitude;
    private Double longitude;
    private Double speed;
    private Double heading;
    private Double altitude;
    @NotNull private Boolean fixValid;
    @Min(0) @Max(6) private Integer fixType;
    @Min(0) @Max(100) private Integer satellites;
    private Double hdop;
}
