package com.premier.rfid;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "rfid_uid_capture_sessions")
@Getter @Setter @NoArgsConstructor
public class RfidUidCaptureSession {
    @Id @Column(name = "request_id", length = 36) private String requestId;
    @Column(name = "device_id", length = 80) private String deviceId;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "rfid_uid", length = 20) private String rfidUid;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "captured_at") private Instant capturedAt;
}
