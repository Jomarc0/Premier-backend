package com.premier.staffcash.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.premier.admin.model.Admin;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "staff_cash_cards", uniqueConstraints = {
        @UniqueConstraint(name = "uk_staff_cash_cards_rfid_uid", columnNames = "rfid_uid"),
        @UniqueConstraint(name = "uk_staff_cash_cards_staff_purpose", columnNames = {"staff_id", "purpose"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffCashCard {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    private Admin staff;

    @Column(name = "rfid_uid", nullable = false, length = 32)
    private String rfidUid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StaffCashCardPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StaffCashCardStatus status = StaffCashCardStatus.ACTIVE;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registered_by", nullable = false)
    private Admin registeredBy;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private LocalDateTime registeredAt;

    @PrePersist
    void onCreate() {
        if (registeredAt == null) registeredAt = LocalDateTime.now();
    }
}
