package com.premier.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "topup_requests",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_topup_reference_number", columnNames = "reference_number"),
                @UniqueConstraint(name = "uk_topup_paymongo_link_id", columnNames = "paymongo_link_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TopUpRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "passenger_id", nullable = false)
    private Passenger passenger;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    private String paymongoCheckoutUrl;
    @Column(name = "paymongo_link_id")
    private String paymongoLinkId;

    @Column(name = "reference_number")
    private String referenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(length = 50) 
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
