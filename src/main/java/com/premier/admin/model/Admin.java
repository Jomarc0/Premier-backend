package com.premier.admin.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "admins")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_id",
        unique = true, nullable = false, length = 20)
    private String adminId;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(name = "full_name",
        nullable = false, length = 100)
    private String fullName;

    @Column(unique = true, length = 100)
    private String email;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @JsonIgnore
    @Column(nullable = false, length = 255)
    private String password;

    @Column(name = "is_2fa_enabled", nullable = false)
    @Builder.Default
    private Boolean is2FaEnabled = false;

    @JsonIgnore
    @Column(name = "twofa_secret")
    private String twofaSecret;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private AdminRole role = AdminRole.ADMIN;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    @Column(name = "login_attempts", nullable = false)
    @Builder.Default
    private Integer loginAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
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

    public boolean isSuperAdmin() {
        return AdminRole.SUPER_ADMIN.equals(this.role);
    }

    public boolean isLocked() {
        return lockedUntil != null &&
            lockedUntil.isAfter(LocalDateTime.now());
    }
}
