package com.premier.rfid;

import com.premier.device.model.DeviceType;
import com.premier.device.security.DevicePrincipal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RfidUidCaptureServiceTest {

    private final DevicePrincipal firstDevice =
            new DevicePrincipal(1L, "terminal-one", DeviceType.VEHICLE_TERMINAL, 10L, "BUS-1", 0);
    private final DevicePrincipal secondDevice =
            new DevicePrincipal(2L, "terminal-two", DeviceType.VEHICLE_TERMINAL, 20L, "BUS-2", 0);

    @Test
    void authenticatedDeviceClaimsAndCompletesCaptureWithoutCreatingFare() {
        var service = new RfidUidCaptureService();
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
        var service = new RfidUidCaptureService();
        String requestId = stringData(service.startCapture(), "requestId");
        service.nextForDevice(firstDevice);

        assertThrows(SecurityException.class,
                () -> service.submitFromDevice(requestId, "04A1B2C3", secondDevice));
        assertEquals("WAITING", stringData(service.status(requestId), "status"));
    }

    @Test
    void explicitlySelectedDeviceIsTheOnlyDeviceThatCanReceiveTheCapture() {
        var service = new RfidUidCaptureService();
        String requestId = stringData(service.startCapture(firstDevice.deviceId()), "requestId");

        assertEquals(Boolean.FALSE, service.nextForDevice(secondDevice).getData().get("active"));
        assertEquals(requestId, stringData(service.nextForDevice(firstDevice), "requestId"));
    }

    @Test
    void oneDeviceKeepsItsExistingWaitingSession() {
        var service = new RfidUidCaptureService();
        String firstRequest = stringData(service.startCapture(), "requestId");
        service.startCapture();

        assertEquals(firstRequest, stringData(service.nextForDevice(firstDevice), "requestId"));
        assertEquals(firstRequest, stringData(service.nextForDevice(firstDevice), "requestId"));
    }

    @Test
    void captureWindowIsThirtySeconds() {
        var response = new RfidUidCaptureService().startCapture();
        var expiresAt = java.time.Instant.parse(stringData(response, "expiresAt"));
        long seconds = java.time.Duration.between(java.time.Instant.now(), expiresAt).toSeconds();
        assertTrue(seconds == 29 || seconds == 30);
        assertEquals(30, RfidUidCaptureService.CAPTURE_TIMEOUT_SECONDS);
    }

    private static String stringData(com.premier.response.ApiResponse<Map<String, Object>> response, String key) {
        return String.valueOf(response.getData().get(key));
    }
}
