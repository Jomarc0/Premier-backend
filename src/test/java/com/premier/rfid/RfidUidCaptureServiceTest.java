package com.premier.rfid;

import com.premier.device.model.DeviceType;
import com.premier.device.security.DevicePrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RfidUidCaptureServiceTest {

    @Autowired private RfidUidCaptureService service;
    @Autowired private RfidUidCaptureSessionRepository sessions;

    private final DevicePrincipal firstDevice =
            new DevicePrincipal(1L, "terminal-one", DeviceType.VEHICLE_TERMINAL, 10L, "BUS-1", 0);
    private final DevicePrincipal secondDevice =
            new DevicePrincipal(2L, "terminal-two", DeviceType.VEHICLE_TERMINAL, 20L, "BUS-2", 0);

    @BeforeEach
    void cleanSessions() {
        sessions.deleteAll();
    }

    @Test
    void authenticatedDeviceClaimsAndCompletesCaptureWithoutCreatingFare() {
        String requestId = stringData(service.startCapture(), "requestId");

        var pending = service.nextForDevice(firstDevice);
        assertEquals(requestId, stringData(pending, "requestId"));
        assertEquals(Boolean.TRUE, pending.getData().get("active"));

        var captured = service.submitFromDevice(requestId, "04:a1-b2 c3", firstDevice);
        assertTrue(captured.isSuccess());
        assertEquals("04A1B2C3", stringData(captured, "rfidUid"));
        assertEquals("CAPTURED", stringData(service.status(requestId), "status"));
    }

    @Test
    void anotherDeviceCannotCompleteAClaimedCapture() {
        String requestId = stringData(service.startCapture(), "requestId");
        service.nextForDevice(firstDevice);

        var rejected = service.submitFromDevice(requestId, "04A1B2C3", secondDevice);
        assertFalse(rejected.isSuccess());
        assertEquals("RFID_CAPTURE_DEVICE_MISMATCH", rejected.getCode());
        assertEquals("WAITING", stringData(service.status(requestId), "status"));
    }

    @Test
    void explicitlySelectedDeviceIsTheOnlyDeviceThatCanReceiveTheCapture() {
        String requestId = stringData(service.startCapture(firstDevice.deviceId()), "requestId");

        assertEquals(Boolean.FALSE, service.nextForDevice(secondDevice).getData().get("active"));
        assertEquals(requestId, stringData(service.nextForDevice(firstDevice), "requestId"));
    }

    @Test
    void oneDeviceKeepsItsExistingWaitingSession() {
        String firstRequest = stringData(service.startCapture(), "requestId");
        service.startCapture();

        assertEquals(firstRequest, stringData(service.nextForDevice(firstDevice), "requestId"));
        assertEquals(firstRequest, stringData(service.nextForDevice(firstDevice), "requestId"));
    }

    @Test
    void captureWindowIsNinetySeconds() {
        var response = service.startCapture();
        var expiresAt = java.time.Instant.parse(stringData(response, "expiresAt"));
        long seconds = java.time.Duration.between(java.time.Instant.now(), expiresAt).toSeconds();
        assertTrue(seconds == 89 || seconds == 90);
        assertEquals(90, RfidUidCaptureService.CAPTURE_TIMEOUT_SECONDS);
    }

    @Test
    void aDifferentBackendServiceInstanceCanCompleteTheSameDatabaseSession() {
        var backendA = new RfidUidCaptureService(sessions);
        var backendB = new RfidUidCaptureService(sessions);
        String requestId = stringData(backendA.startCapture(firstDevice.deviceId()), "requestId");

        assertEquals(requestId, stringData(backendB.nextForDevice(firstDevice), "requestId"));
        var captured = backendB.submitFromDevice(requestId, "A367F939", firstDevice);

        assertTrue(captured.isSuccess());
        assertEquals("RFID_CAPTURED", captured.getCode());
    }

    @Test
    void expiredCaptureReturnsExpiredCodeAndDoesNotAllowSubmission() {
        // Create an already-expired session directly in the database
        RfidUidCaptureSession expiredSession = new RfidUidCaptureSession();
        expiredSession.setRequestId(UUID.randomUUID().toString());
        expiredSession.setDeviceId(firstDevice.deviceId());
        expiredSession.setStatus("WAITING");
        expiredSession.setCreatedAt(Instant.now().minusSeconds(10));
        expiredSession.setExpiresAt(Instant.now().minusSeconds(5)); // Already expired
        sessions.saveAndFlush(expiredSession);
        String requestId = expiredSession.getRequestId();

        var expired = service.submitFromDevice(requestId, "A367F939", firstDevice);
        assertFalse(expired.isSuccess());
        assertEquals("RFID_CAPTURE_EXPIRED", expired.getCode());
        assertEquals("EXPIRED", stringData(expired, "status"));
        assertEquals("EXPIRED", stringData(service.status(requestId), "status"));
    }

    private static String stringData(com.premier.response.ApiResponse<Map<String, Object>> response, String key) {
        return String.valueOf(response.getData().get(key));
    }
}
