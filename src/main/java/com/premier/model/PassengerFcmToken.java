package com.premier.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "passenger_fcm_tokens", indexes = @Index(name = "idx_passenger_fcm_tokens_passenger", columnList = "passenger_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PassengerFcmToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "passenger_id", nullable = false)
    private Passenger passenger;

    @Column(name = "fcm_token", nullable = false, unique = true, length = 512)
    private String fcmToken;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist @PreUpdate
    void updateTimestamp() { updatedAt = LocalDateTime.now(); }
}
