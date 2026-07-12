package com.premier.staffcash.model;

import com.premier.admin.model.Admin;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "staff_cash_remittances", uniqueConstraints =
        @UniqueConstraint(name = "uk_staff_cash_remittance_staff_date", columnNames = {"staff_id", "collection_date"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StaffCashRemittance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    private Admin staff;
    @Column(name = "collection_date", nullable = false)
    private LocalDate collectionDate;
    @Column(name = "expected_cash", nullable = false, precision = 12, scale = 2)
    private BigDecimal expectedCash;
    @Column(name = "actual_cash_received", nullable = false, precision = 12, scale = 2)
    private BigDecimal actualCashReceived;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal difference;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StaffRemittanceStatus status;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "confirmed_by", nullable = false)
    private Admin confirmedBy;
    @Column(name = "confirmed_at", nullable = false)
    private LocalDateTime confirmedAt;
}
