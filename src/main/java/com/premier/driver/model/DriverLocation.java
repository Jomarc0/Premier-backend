package com.premier.driver.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "driver_locations", indexes = {
    @Index(name = "idx_loc_plate",
           columnList = "plate_number"),
    @Index(name = "idx_loc_shift",
           columnList = "shift_id"),
    @Index(name = "idx_loc_recorded",
           columnList = "recorded_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@EqualsAndHashCode(of = "id")
public class DriverLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plate_number", nullable = false, length = 20)
    private String plateNumber;

    // nullable
    @Column(name = "shift_id")
    private Long shiftId;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Builder.Default
    private Double speed = 0.0;

    @Builder.Default
    private Double heading = 0.0;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (recordedAt == null) recordedAt = LocalDateTime.now();
    }
}