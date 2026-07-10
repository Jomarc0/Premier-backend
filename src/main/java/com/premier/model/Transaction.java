package com.premier.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.premier.driver.model.DriverShift;
import com.premier.driver.model.Vehicle;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_transactions_reference_number", columnNames = "reference_number"),
                @UniqueConstraint(name = "uk_transactions_idempotency_key", columnNames = "idempotency_key")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "passenger_id", nullable = false)
    private Passenger passenger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(precision = 10, scale = 2)
    private BigDecimal balanceBefore;

    @Column(precision = 10, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "reference_number")
    private String referenceNumber;

    @Column(name = "idempotency_key", length = 120)
    private String idempotencyKey;

    @Column(name = "device_id", length = 80)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 30)
    private PaymentMethod paymentMethod;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_shift_id")
    private DriverShift driverShift;

    @Column(name = "route_snapshot", length = 160)
    private String routeSnapshot;

    @Column(name = "request_nonce", length = 120)
    private String requestNonce;

    @Column(name = "request_timestamp")
    private LocalDateTime requestTimestamp;

    private String description;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
