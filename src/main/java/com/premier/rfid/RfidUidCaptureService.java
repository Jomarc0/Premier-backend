package com.premier.rfid;

import com.premier.device.security.DevicePrincipal;
import com.premier.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class RfidUidCaptureService {

    static final long CAPTURE_TIMEOUT_SECONDS = 30;
    private static final long COMPLETED_RETENTION_SECONDS = 30;
    private final Map<String, CaptureSession> sessions = new ConcurrentHashMap<>();
    private long nextSequence;

    public synchronized ApiResponse<Map<String, Object>> startCapture() {
        return startCapture(null);
    }

    public synchronized ApiResponse<Map<String, Object>> startCapture(String requestedDeviceId) {
        expireOldSessions();
        String deviceId = requestedDeviceId == null || requestedDeviceId.isBlank()
                ? null : requestedDeviceId.trim();
        if (deviceId != null) {
            CaptureSession existing = sessions.values().stream()
                    .filter(session -> "WAITING".equals(session.status) && deviceId.equals(session.deviceId))
                    .min(Comparator.comparingLong(session -> session.sequence))
                    .orElse(null);
            if (existing != null) {
                return startResponse(existing, "RFID UID capture is already waiting for this device.");
            }
        }
        if (sessions.size() >= 100) throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "CAPTURE_CAPACITY", "Too many active capture requests.");
        String requestId = UUID.randomUUID().toString();
        CaptureSession session = new CaptureSession(requestId, nextSequence++);
        session.deviceId = deviceId;
        sessions.put(requestId, session);
        log.info("[RFID CAPTURE] Started captureId={} device={}", requestId,
                deviceId == null ? "unassigned" : deviceId);
        return startResponse(session, "Tap a blank RFID card on the reader.");
    }

    private ApiResponse<Map<String, Object>> startResponse(CaptureSession session, String message) {
        return ApiResponse.success(message,
                dataMap(
                        "requestId", session.requestId,
                        "status", session.status,
                        "expiresAt", session.expiresAt.toString()));
    }

    public synchronized ApiResponse<Map<String, Object>> status(String requestId) {
        expireOldSessions();
        CaptureSession session = sessions.get(requestId);
        if (session == null) {
            log.info("[RFID CAPTURE] Expired captureId={}", requestId);
            return ApiResponse.success("RFID UID capture expired.",
                    dataMap("status", "EXPIRED"));
        }

        log.info("[RFID CAPTURE] {} captureId={}", session.status, requestId);
        return ApiResponse.success(messageFor(session),
                dataMap(
                        "requestId", session.requestId,
                        "status", session.status,
                        "rfidUid", session.rfidUid == null ? "" : session.rfidUid,
                        "deviceId", session.deviceId == null ? "" : session.deviceId,
                        "expiresAt", session.expiresAt.toString()));
    }

    public synchronized ApiResponse<Map<String, Object>> nextForDevice(DevicePrincipal device) {
        expireOldSessions();
        if (device == null) {
            throw new SecurityException("Authenticated device identity is required.");
        }

        CaptureSession assigned = sessions.values().stream()
                .filter(session -> "WAITING".equals(session.status)
                        && device.deviceId().equals(session.deviceId))
                .min(Comparator.comparingLong(session -> session.sequence))
                .orElse(null);

        if (assigned == null) {
            assigned = sessions.values().stream()
                    .filter(session -> "WAITING".equals(session.status) && session.deviceId == null)
                    .min(Comparator.comparingLong(session -> session.sequence))
                    .orElse(null);
            if (assigned != null) {
                assigned.deviceId = device.deviceId();
                log.info("[RFID CAPTURE] Waiting captureId={} device={}", assigned.requestId, assigned.deviceId);
            }
        }

        if (assigned != null) {
            return ApiResponse.success("RFID UID capture requested.",
                    dataMap(
                            "active", true,
                            "requestId", assigned.requestId,
                            "expiresInMs", Math.max(0, Duration.between(Instant.now(), assigned.expiresAt).toMillis()),
                            "message", "Tap RFID card to register"));
        }

        return ApiResponse.success("No RFID UID capture requested.", dataMap("active", false));
    }

    public synchronized ApiResponse<Map<String, Object>> submitFromDevice(String requestId, String uid, DevicePrincipal device) {
        expireOldSessions();
        CaptureSession session = sessions.get(requestId);
        if (session == null || session.isExpired()) {
            return ApiResponse.error("RFID UID capture expired.");
        }

        if (device == null || !device.deviceId().equals(session.deviceId) || !"WAITING".equals(session.status)) {
            throw new SecurityException("Capture session is not assigned to this device.");
        }
        String normalizedUid = normalizeUid(uid);
        if (!normalizedUid.matches("(?:[A-F0-9]{8}|[A-F0-9]{14}|[A-F0-9]{20})")) {
            return ApiResponse.error("Invalid RFID UID.");
        }

        session.status = "CAPTURED";
        session.rfidUid = normalizedUid;
        session.deviceId = device != null ? device.deviceId() : null;
        session.capturedAt = Instant.now();
        log.info("[RFID CAPTURE] UID submitted captureId={} device={} uid={}", requestId, device.deviceId(), normalizedUid);
        log.info("[RFID CAPTURE] Completed captureId={} device={}", requestId, device.deviceId());

        return ApiResponse.success("RFID UID captured.",
                dataMap(
                        "requestId", session.requestId,
                        "status", session.status,
                        "rfidUid", session.rfidUid));
    }

    private String normalizeUid(String uid) {
        if (uid == null) return "";
        return uid.trim()
                .toUpperCase()
                .replace("UID", "")
                .replace(":", "")
                .replace(" ", "")
                .replaceAll("[^A-F0-9]", "");
    }

    private Map<String, Object> dataMap(Object... values) {
        Map<String, Object> data = new HashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            data.put(String.valueOf(values[i]), values[i + 1]);
        }
        return data;
    }

    private void expireOldSessions() {
        sessions.values().removeIf(session -> {
            boolean expired = session.shouldRemove();
            if (expired) log.info("[RFID CAPTURE] Expired captureId={} device={}", session.requestId,
                    session.deviceId == null ? "unassigned" : session.deviceId);
            return expired;
        });
    }

    private String messageFor(CaptureSession session) {
        return "CAPTURED".equals(session.status)
                ? "RFID UID captured."
                : "Waiting for RFID card tap.";
    }

    private static class CaptureSession {
        private final String requestId;
        private final long sequence;
        private final Instant createdAt;
        private final Instant expiresAt;
        private String status = "WAITING";
        private String rfidUid;
        private String deviceId;
        private Instant capturedAt;

        private CaptureSession(String requestId, long sequence) {
            this.requestId = requestId;
            this.sequence = sequence;
            this.createdAt = Instant.now();
            this.expiresAt = createdAt.plusSeconds(CAPTURE_TIMEOUT_SECONDS);
        }

        private boolean isExpired() {
            return "WAITING".equals(status) && Instant.now().isAfter(expiresAt);
        }

        private boolean shouldRemove() {
            if (isExpired()) return true;
            return "CAPTURED".equals(status) && capturedAt != null
                    && Instant.now().isAfter(capturedAt.plusSeconds(COMPLETED_RETENTION_SECONDS));
        }
    }
}
