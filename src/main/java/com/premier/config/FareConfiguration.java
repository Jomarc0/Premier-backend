package com.premier.config;

import com.premier.admin.model.Admin;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "fare_configurations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FareConfiguration {
    @Id private Long id;
    @Column(name="regular_fare", nullable=false, precision=10, scale=2) private BigDecimal regularFare;
    @Column(name="discounted_fare", nullable=false, precision=10, scale=2) private BigDecimal discountedFare;
    @Column(nullable=false, length=3) @Builder.Default private String currency = "PHP";
    @Version @Column(nullable=false) private Long version;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="updated_by_admin_id") private Admin updatedBy;
    @Column(name="created_at", nullable=false, updatable=false) private LocalDateTime createdAt;
    @Column(name="updated_at", nullable=false) private LocalDateTime updatedAt;
    @PrePersist void create() { createdAt=LocalDateTime.now(); updatedAt=createdAt; }
    @PreUpdate void update() { updatedAt=LocalDateTime.now(); }
}
