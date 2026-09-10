package com.premier.payment.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** Cross-ledger identity guard committed atomically with the financial effect. */
@Entity @Table(name = "payment_intent_identities")
@Getter @Setter @NoArgsConstructor
public class PaymentIntentIdentity {
    @Id @Column(length = 120) private String id;
    @Column(nullable = false, length = 80) private String deviceId;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(unique = true, length = 120) private String offlineId;
    @Column(nullable = false) private Instant createdAt;
}
