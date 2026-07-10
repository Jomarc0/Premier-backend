package com.premier.payment.model;

import com.premier.driver.model.DriverShift;
import com.premier.driver.model.Vehicle;
import com.premier.model.Passenger;
import com.premier.model.PaymentMethod;
import com.premier.model.Transaction;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "fare_payment_attempts", indexes = {
        @Index(name = "idx_fare_attempt_created_at", columnList = "created_at"),
        @Index(name = "idx_fare_attempt_status", columnList = "status"),
        @Index(name = "idx_fare_attempt_payment_method", columnList = "payment_method"),
        @Index(name = "idx_fare_attempt_vehicle", columnList = "vehicle_id"),
        @Index(name = "idx_fare_attempt_device", columnList = "device_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FarePaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "passenger_id")
    private Passenger passenger;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 30)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FarePaymentAttemptStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", nullable = false, length = 40)
    @Builder.Default
    private FarePaymentFailureReason failureReason = FarePaymentFailureReason.NONE;

    @Column(precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "masked_rfid_uid", length = 40)
    private String maskedRfidUid;

    @Column(name = "device_id", length = 80)
    private String deviceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_shift_id")
    private DriverShift driverShift;

    @Column(name = "route_snapshot", length = 160)
    private String routeSnapshot;

    @Column(name = "request_nonce", length = 120)
    private String requestNonce;

    @Column(name = "request_timestamp")
    private LocalDateTime requestTimestamp;

    @Column(name = "failure_message", length = 240)
    private String failureMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
