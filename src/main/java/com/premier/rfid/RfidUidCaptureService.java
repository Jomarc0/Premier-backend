package com.premier.rfid;

import com.premier.device.security.DevicePrincipal;
import com.premier.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RfidUidCaptureService {
    static final long CAPTURE_TIMEOUT_SECONDS = 90;
    private static final long FINISHED_RETENTION_SECONDS = 86400;
    private static final String WAITING = "WAITING";
    private static final String CAPTURED = "CAPTURED";
    private static final String EXPIRED = "EXPIRED";
    private static final String BACKEND_INSTANCE = resolveBackendInstance();

    private final RfidUidCaptureSessionRepository sessions;

    @Transactional
    public ApiResponse<Map<String, Object>> startCapture() {
        return startCapture(null);
    }

    @Transactional
    public ApiResponse<Map<String, Object>> startCapture(String requestedDeviceId) {
        Instant now = Instant.now();
        expireAndClean(now);
        String deviceId = requestedDeviceId == null || requestedDeviceId.isBlank()
                ? null : requestedDeviceId.trim();

        if (deviceId != null) {
            var existing = sessions.findFirstByDeviceIdAndStatusOrderByCreatedAtAsc(deviceId, WAITING);
            if (existing.isPresent()) {
                logSession("START REUSED", existing.get(), now);
                return startResponse(existing.get(), "RFID UID capture is already waiting for this device.");
            }
        }

        RfidUidCaptureSession session = new RfidUidCaptureSession();
        session.setRequestId(UUID.randomUUID().toString());
        session.setDeviceId(deviceId);
        session.setStatus(WAITING);
        session.setCreatedAt(now);
        session.setExpiresAt(now.plusSeconds(CAPTURE_TIMEOUT_SECONDS));
        sessions.saveAndFlush(session);
        logSession("START", session, now);
        return startResponse(session, "Tap a blank RFID card on the reader.");
    }

    @Transactional
    public ApiResponse<Map<String, Object>> status(String requestId) {
        Instant now = Instant.now();
        expireAndClean(now);
        RfidUidCaptureSession session = sessions.findById(requestId).orElse(null);
        if (session == null) {
            logMissing("STATUS NOT FOUND", requestId, now);
            return response(true, "RFID_CAPTURE_NOT_FOUND", "RFID UID capture is no longer available.",
                    dataMap("requestId", requestId, "status", EXPIRED, "backendInstance", BACKEND_INSTANCE));
        }
        logSession("STATUS", session, now);
        return response(true, "RFID_CAPTURE_" + session.getStatus(), messageFor(session), sessionData(session, now));
    }

    @Transactional
    public ApiResponse<Map<String, Object>> nextForDevice(DevicePrincipal device) {
        if (device == null) throw new SecurityException("Authenticated device identity is required.");
        Instant now = Instant.now();
        expireAndClean(now);

        RfidUidCaptureSession session = sessions
                .findFirstByDeviceIdAndStatusOrderByCreatedAtAsc(device.deviceId(), WAITING).orElse(null);
        if (session == null) {
            session = sessions.findFirstByDeviceIdIsNullAndStatusOrderByCreatedAtAsc(WAITING).orElse(null);
            if (session != null) {
                session.setDeviceId(device.deviceId());
                sessions.saveAndFlush(session);
            }
        }

        if (session == null) {
            return response(true, "RFID_CAPTURE_IDLE", "No RFID UID capture requested.",
                    dataMap("active", false, "backendInstance", BACKEND_INSTANCE));
        }

        logSession("DEVICE CLAIM", session, now);
        Map<String, Object> data = sessionData(session, now);
        data.put("active", true);
        data.put("expiresInMs", remainingMillis(session, now));
        data.put("message", "Tap RFID card to register");
        return response(true, "RFID_CAPTURE_WAITING", "RFID UID capture requested.", data);
    }

    @Transactional
    public ApiResponse<Map<String, Object>> submitFromDevice(String requestId, String uid, DevicePrincipal device) {
        Instant now = Instant.now();
        String normalizedUid = normalizeUid(uid);
        RfidUidCaptureSession session = sessions.findLockedByRequestId(requestId).orElse(null);
        if (session == null) {
            logMissing("UID SUBMISSION NOT FOUND", requestId, now);
            return response(false, "RFID_CAPTURE_NOT_FOUND", "RFID UID capture session was not found.",
                    dataMap("requestId", requestId, "backendInstance", BACKEND_INSTANCE, "now", now.toString()));
        }

        boolean active = WAITING.equals(session.getStatus()) && now.isBefore(session.getExpiresAt());
        logSession("UID SUBMISSION active=" + active, session, now);
        if (!active) {
            if (WAITING.equals(session.getStatus())) {
                session.setStatus(EXPIRED);
                sessions.saveAndFlush(session);
            }
            return response(false, "RFID_CAPTURE_EXPIRED", "RFID UID capture expired.", sessionData(session, now));
        }
        if (device == null || !device.deviceId().equals(session.getDeviceId())) {
            return response(false, "RFID_CAPTURE_DEVICE_MISMATCH",
                    "RFID UID capture belongs to another device.", sessionData(session, now));
        }
        if (!normalizedUid.matches("(?:[A-F0-9]{8}|[A-F0-9]{14}|[A-F0-9]{20})")) {
            return response(false, "INVALID_RFID_UID", "Invalid RFID UID.", sessionData(session, now));
        }

        session.setStatus(CAPTURED);
        session.setRfidUid(normalizedUid);
        session.setCapturedAt(now);
        sessions.saveAndFlush(session);
        logSession("COMPLETED uid=" + normalizedUid, session, now);
        return response(true, "RFID_CAPTURED", "RFID UID captured.", sessionData(session, now));
    }

    private ApiResponse<Map<String, Object>> startResponse(RfidUidCaptureSession session, String message) {
        return response(true, "RFID_CAPTURE_WAITING", message, sessionData(session, Instant.now()));
    }

    private void expireAndClean(Instant now) {
        int expired = sessions.expireWaiting(now);
        int deleted = sessions.deleteOldFinished(now.minusSeconds(FINISHED_RETENTION_SECONDS));
        if (expired > 0 || deleted > 0) {
            log.info("[RFID CAPTURE SESSION] CLEANUP expired={} deleted={} now={} backend={}",
                    expired, deleted, now, BACKEND_INSTANCE);
        }
    }

    private Map<String, Object> sessionData(RfidUidCaptureSession session, Instant now) {
        return dataMap("requestId", session.getRequestId(), "status", session.getStatus(),
                "rfidUid", session.getRfidUid() == null ? "" : session.getRfidUid(),
                "deviceId", session.getDeviceId() == null ? "" : session.getDeviceId(),
                "createdAt", session.getCreatedAt().toString(), "expiresAt", session.getExpiresAt().toString(),
                "now", now.toString(), "remainingMs", remainingMillis(session, now),
                "backendInstance", BACKEND_INSTANCE);
    }

    private long remainingMillis(RfidUidCaptureSession session, Instant now) {
        return Math.max(0, Duration.between(now, session.getExpiresAt()).toMillis());
    }

    private void logSession(String event, RfidUidCaptureSession session, Instant now) {
        log.info("[RFID CAPTURE SESSION] {} sessionId={} device={} status={} createdAt={} expiresAt={} now={} remaining={}ms active={} backend={}",
                event, session.getRequestId(), session.getDeviceId(), session.getStatus(), session.getCreatedAt(),
                session.getExpiresAt(), now, remainingMillis(session, now),
                WAITING.equals(session.getStatus()) && now.isBefore(session.getExpiresAt()), BACKEND_INSTANCE);
    }

    private void logMissing(String event, String requestId, Instant now) {
        log.warn("[RFID CAPTURE SESSION] {} sessionId={} now={} backend={}", event, requestId, now, BACKEND_INSTANCE);
    }

    private ApiResponse<Map<String, Object>> response(boolean success, String code, String message, Map<String, Object> data) {
        return ApiResponse.<Map<String, Object>>builder().success(success).code(code).message(message).data(data).build();
    }

    private String normalizeUid(String uid) {
        if (uid == null) return "";
        return uid.trim().toUpperCase().replace("UID", "").replace(":", "")
                .replace(" ", "").replaceAll("[^A-F0-9]", "");
    }

    private Map<String, Object> dataMap(Object... values) {
        Map<String, Object> data = new HashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) data.put(String.valueOf(values[i]), values[i + 1]);
        return data;
    }

    private String messageFor(RfidUidCaptureSession session) {
        if (CAPTURED.equals(session.getStatus())) return "RFID UID captured.";
        if (EXPIRED.equals(session.getStatus())) return "RFID UID capture expired.";
        return "Waiting for RFID card tap.";
    }

    private static String resolveBackendInstance() {
        String render = System.getenv("RENDER_INSTANCE_ID");
        if (render != null && !render.isBlank()) return render;
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) return hostname;
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception ignored) { return "local"; }
    }
}
