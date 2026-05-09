package com.premier.driver.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "driver_shifts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverShift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    private Driver driver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    private LocalDateTime shiftStart;
    private LocalDateTime shiftEnd;

    @Builder.Default
    private int passengersServed = 0;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ShiftStatus status = ShiftStatus.ACTIVE;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "current_latitude")
    private Double currentLatitude;

    @Column(name = "current_longitude") 
    private Double currentLongitude;

    @Column(name = "last_location_update")
    private LocalDateTime lastLocationUpdate;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        shiftStart = LocalDateTime.now();
    }
}