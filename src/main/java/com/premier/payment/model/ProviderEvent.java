package com.premier.payment.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
@Entity @Table(name = "provider_events") @Getter @Setter @NoArgsConstructor
public class ProviderEvent {
    @Id @Column(length = 120) private String id;
    @Column(nullable = false, length = 120) private String resourceId;
    @Column(nullable = false, length = 64) private String payloadHash;
    @Column(nullable = false, length = 40) private String status;
    @Column(length = 80) private String eventType;
    @Column(nullable = false) private Instant receivedAt;
}
