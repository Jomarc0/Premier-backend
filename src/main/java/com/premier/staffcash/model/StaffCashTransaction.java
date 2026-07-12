package com.premier.staffcash.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.premier.admin.model.Admin;
import com.premier.driver.model.DriverShift;
import com.premier.driver.model.Vehicle;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "staff_cash_transactions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_staff_cash_tx_reference", columnNames = "reference_number"),
        @UniqueConstraint(name = "uk_staff_cash_tx_idempotency", columnNames = "idempotency_key"),
        @UniqueConstraint(name = "uk_staff_cash_tx_offline_id", columnNames = "offline_transaction_id")
}, indexes = {
        @Index(name = "idx_staff_cash_tx_staff_created", columnList = "staff_id,created_at"),
        @Index(name = "idx_staff_cash_tx_vehicle_created", columnList = "vehicle_id,created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffCashTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    private Admin staff;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operation_card_id", nullable = false)
    private StaffCashCard operationCard;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_shift_id", nullable = false)
    private DriverShift driverShift;

    @Column(name = "device_id", nullable = false, length = 80)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fare_category", nullable = false, length = 30)
    private StaffCashCardPurpose fareCategory;

    @Column(name = "base_fare", nullable = false, precision = 10, scale = 2)
    private BigDecimal baseFare;

    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "final_fare", nullable = false, precision = 10, scale = 2)
    private BigDecimal finalFare;

    @Column(name = "reference_number", nullable = false, length = 40)
    private String referenceNumber;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "offline_transaction_id", length = 120)
    private String offlineTransactionId;

    @Column(name = "offline_captured_at")
    private LocalDateTime offlineCapturedAt;

    @Column(name = "route_snapshot", length = 160)
    private String routeSnapshot;

    @Column(name = "terminal_snapshot", length = 100)
    private String terminalSnapshot;

    @Column(name = "request_timestamp")
    private LocalDateTime requestTimestamp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
