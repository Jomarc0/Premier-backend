package com.premier.payment.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
@Entity @Table(name = "payment_notifications", uniqueConstraints = @UniqueConstraint(columnNames = {"reference", "kind"}))
@Getter @Setter @NoArgsConstructor
public class PaymentNotification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long passengerId;
    @Column(nullable = false, length = 120) private String reference;
    @Column(nullable = false, length = 16) private String kind;
    @Column(nullable = false, length = 16) private String status = "PENDING";
    @Column(nullable = false) private int attempts;
    @Column(nullable = false) private Instant dueAt = Instant.now();
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
