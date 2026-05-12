package com.premier.driver.model;

import com.premier.model.Passenger;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "passenger_onboards")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PassengerOnboard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    private DriverShift shift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "passenger_id", nullable = false)
    private Passenger passenger;

    @Column(nullable = false)
    private String dropOffLocation;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fare;

    @Builder.Default
    private int passengerCount = 1;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private OnboardStatus status = OnboardStatus.ONBOARD;

    private LocalDateTime boardedAt;
    private LocalDateTime droppedOffAt;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        boardedAt = LocalDateTime.now();
    }
}