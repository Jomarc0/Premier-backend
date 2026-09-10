package com.premier.staffcash.model;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
@org.hibernate.annotations.Immutable
@Entity @Table(name="staff_cash_remittance_adjustments") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StaffCashRemittanceAdjustment {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,updatable=false) private Long remittanceId;
    @Column(nullable=false,updatable=false) private Long authorizedBy;
    @Column(nullable=false,updatable=false,precision=12,scale=2) private BigDecimal amount;
    @Column(nullable=false,updatable=false,length=240) private String reason;
    @Column(nullable=false,updatable=false,length=80,unique=true) private String requestId;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @PrePersist void create() { createdAt=Instant.now(); }
}
