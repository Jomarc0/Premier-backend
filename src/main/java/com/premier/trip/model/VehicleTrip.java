package com.premier.trip.model;

import com.premier.driver.model.DriverShift;
import com.premier.driver.model.Vehicle;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "vehicle_trips", indexes = {
        @Index(name = "idx_vehicle_trip_vehicle_started", columnList = "vehicle_id,started_at"),
        @Index(name = "idx_vehicle_trip_status", columnList = "status"),
        @Index(name = "idx_vehicle_trip_shift", columnList = "driver_shift_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VehicleTrip {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(name = "vehicle_plate_number", nullable = false, length = 40)
    private String vehiclePlateNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_shift_id", nullable = false)
    private DriverShift driverShift;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TripDirection direction;

    @Column(name = "origin_terminal", nullable = false, length = 80)
    private String originTerminal;

    @Column(name = "destination_terminal", nullable = false, length = 80)
    private String destinationTerminal;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TripStatus status = TripStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (startedAt == null) startedAt = LocalDateTime.now();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
