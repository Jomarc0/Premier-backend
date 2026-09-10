package com.premier.rfid;

import com.premier.device.security.DevicePrincipal;
import com.premier.response.ApiResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RfidUidCaptureService {

    private static final long CAPTURE_TIMEOUT_SECONDS = 60;
    private final Map<String, CaptureSession> sessions = new ConcurrentHashMap<>();

    public synchronized ApiResponse<Map<String, Object>> startCapture() {
        expireOldSessions();
        if (sessions.size() >= 100) throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "CAPTURE_CAPACITY", "Too many active capture requests.");
        String requestId = UUID.randomUUID().toString();
        CaptureSession session = new CaptureSession(requestId);
        sessions.put(requestId, session);
        return ApiResponse.success("Tap a blank RFID card on the reader.",
                dataMap(
                        "requestId", requestId,
                        "status", session.status,
                        "expiresAt", session.expiresAt.toString()));
    }

    public synchronized ApiResponse<Map<String, Object>> status(String requestId) {
        expireOldSessions();
        CaptureSession session = sessions.get(requestId);
        if (session == null) {
            return ApiResponse.success("RFID UID capture expired.",
                    dataMap("status", "EXPIRED"));
        }

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
        return sessions.values().stream()
                .filter(session -> "WAITING_FOR_CARD".equals(session.status)
                        && device != null && (session.deviceId == null || session.deviceId.equals(device.deviceId())))
                .min(Comparator.comparing(session -> session.createdAt))
                .map(session -> {
                    session.deviceId = device != null ? device.deviceId() : null;
                    return ApiResponse.success("RFID UID capture requested.",
                            dataMap(
                                    "active", true,
                                    "requestId", session.requestId,
                                    "message", "Tap RFID card to register"));
                })
                .orElseGet(() -> ApiResponse.success("No RFID UID capture requested.",
                        dataMap("active", false)));
    }

    public synchronized ApiResponse<Map<String, Object>> submitFromDevice(String requestId, String uid, DevicePrincipal device) {
        expireOldSessions();
        CaptureSession session = sessions.get(requestId);
        if (session == null || session.isExpired()) {
            return ApiResponse.error("RFID UID capture expired.");
        }

        if (device == null || !device.deviceId().equals(session.deviceId) || !"WAITING_FOR_CARD".equals(session.status)) {
            throw new SecurityException("Capture session is not assigned to this device.");
        }
        String normalizedUid = normalizeUid(uid);
        if (!normalizedUid.matches("(?:[A-F0-9]{8}|[A-F0-9]{14}|[A-F0-9]{20})")) {
            return ApiResponse.error("Invalid RFID UID.");
        }

        session.status = "CAPTURED";
        session.rfidUid = normalizedUid;
        session.deviceId = device != null ? device.deviceId() : null;
        session.capturedAt = LocalDateTime.now();

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
        sessions.values().removeIf(CaptureSession::isExpired);
    }

    private String messageFor(CaptureSession session) {
        return "CAPTURED".equals(session.status)
                ? "RFID UID captured."
                : "Waiting for RFID card tap.";
    }

    private static class CaptureSession {
        private final String requestId;
        private final LocalDateTime createdAt;
        private final LocalDateTime expiresAt;
        private String status = "WAITING_FOR_CARD";
        private String rfidUid;
        private String deviceId;
        private LocalDateTime capturedAt;

        private CaptureSession(String requestId) {
            this.requestId = requestId;
            this.createdAt = LocalDateTime.now();
            this.expiresAt = createdAt.plusSeconds(CAPTURE_TIMEOUT_SECONDS);
        }

        private boolean isExpired() {
            return LocalDateTime.now().isAfter(expiresAt);
        }
    }
}
