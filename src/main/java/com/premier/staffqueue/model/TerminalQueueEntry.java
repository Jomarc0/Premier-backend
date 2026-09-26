package com.premier.staffqueue.model;

import com.premier.admin.model.Admin;
import com.premier.driver.model.Vehicle;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "terminal_queue_entries", indexes = {
        @Index(name = "idx_terminal_queue_order", columnList = "terminal,status,checked_in_at,id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TerminalQueueEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TerminalCode terminal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(name = "vehicle_plate_number", nullable = false, length = 40)
    private String vehiclePlateNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TerminalQueueStatus status = TerminalQueueStatus.WAITING;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_in_source", nullable = false, length = 20)
    private QueueCheckInSource checkInSource;

    @Column(name = "checked_in_at", nullable = false)
    private Instant checkedInAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checked_in_by")
    private Admin checkedInBy;

    @Column(name = "boarding_at")
    private Instant boardingAt;

    @Column(name = "departed_at")
    private Instant departedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "rearmed_at")
    private Instant rearmedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (checkedInAt == null) checkedInAt = now;
        updatedAt = now;
        if (vehiclePlateNumber == null && vehicle != null) vehiclePlateNumber = vehicle.getPlateNumber();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
