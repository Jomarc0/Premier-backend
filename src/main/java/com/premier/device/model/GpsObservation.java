package com.premier.device.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
@Entity @Table(name = "gps_observations", indexes = @Index(columnList = "device_id,received_at"))
@Getter @Setter @NoArgsConstructor
public class GpsObservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 80) private String deviceId;
    @Column(nullable = false, length = 40) private String plateNumber;
    private Long shiftId;
    private Instant capturedAt;
    @Column(nullable = false) private Instant receivedAt;
    @Column(nullable = false, length = 24) private String status;
    @Column(length = 40) private String reason;
    private Double latitude;
    private Double longitude;
    private Integer satellites;
    private Double hdop;
    private Integer fixType;
}
