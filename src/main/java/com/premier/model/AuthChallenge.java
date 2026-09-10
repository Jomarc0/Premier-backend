package com.premier.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** Server-side, single-use authorization; a signed JWT alone is not enrollment proof. */
@Entity
@Table(name = "auth_challenges", indexes = @Index(name = "idx_auth_challenge_expiry", columnList = "expires_at"))
@Getter @Setter @NoArgsConstructor
public class AuthChallenge {
    @Id @Column(length = 36) private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "passenger_id", nullable = false)
    private Passenger passenger;
    @Column(nullable = false, length = 16) private String purpose;
    @Column(nullable = false) private Instant expiresAt;
    private Instant usedAt;
    @Column(nullable = false) private long sessionVersion;
    @Column(unique = true) private Long supportTicketId;
    private Long authorizedBy;
}
