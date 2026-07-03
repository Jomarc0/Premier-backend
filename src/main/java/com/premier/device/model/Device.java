package com.premier.device.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "devices",
        indexes = {
                @Index(name = "idx_devices_plate_number", columnList = "plate_number")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, unique = true, length = 80)
    private String deviceId;

    @Column(name = "device_name", nullable = false, length = 120)
    private String deviceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false, length = 40)
    private DeviceType deviceType;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "plate_number", length = 40)
    private String plateNumber;

    @JsonIgnore
    @Column(name = "token_hash", nullable = false, length = 255)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private DeviceStatus status = DeviceStatus.ACTIVE;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return status == DeviceStatus.ACTIVE && revokedAt == null;
    }
}
