package com.premier.device.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "device_request_nonces",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_device_nonce", columnNames = {"device_id", "nonce"})
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceNonce {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Column(nullable = false, length = 120)
    private String nonce;

    @Column(name = "request_timestamp", nullable = false)
    private LocalDateTime requestTimestamp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
