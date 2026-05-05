package com.premier.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "passengers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Passenger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "fcm_token")
    private String fcmToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @Builder.Default
    private PassengerStatus status = PassengerStatus.ACTIVE;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "card_number", unique = true, nullable = false)
    private String cardNumber;

    @Column(name = "rfid_uid", unique = true)
    private String rfidUid;

    @Column(name = "is_2fa_enabled", columnDefinition = "BIT(1) DEFAULT 0")
    @Builder.Default
    private Boolean is2FaEnabled = false;

    @Column(name = "twofa_secret")
    private String twofaSecret;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}