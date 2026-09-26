package com.premier.service;
import com.premier.device.model.*;
import com.premier.device.repository.*;
import com.premier.device.request.*;
import com.premier.device.security.DevicePrincipal;
import com.premier.device.service.*;
import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @ActiveProfiles("test")
class GpsDeviceRegressionTest {
    @Autowired GpsTelemetryService gps;
    @Autowired DeviceHealthService health;
    @Autowired DeviceService auth;
    @Autowired DeviceRepository devices;
    @Autowired VehicleRepository vehicles;
    @Autowired DriverLocationRepository locations;
    @Autowired GpsObservationRepository observations;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    private DevicePrincipal terminal() {
        String id=UUID.randomUUID().toString(); String plate=id.substring(0,8).toUpperCase();
        var vehicle=vehicles.saveAndFlush(Vehicle.builder().plateNumber(plate).totalCapacity(50)
                .status(VehicleStatus.ACTIVE).build());
        return DevicePrincipal.from(devices.saveAndFlush(Device.builder().deviceId(id).deviceName("Synthetic GPS terminal")
                .vehicleId(vehicle.getId()).plateNumber(plate).deviceType(DeviceType.VEHICLE_TERMINAL).tokenHash(encoder.encode(id)).build()));
    }
    private GpsTelemetryRequest fix(DevicePrincipal device, Instant captured) {
        var r=new GpsTelemetryRequest(); r.setPlateNumber(device.plateNumber()); r.setRequestNonce(UUID.randomUUID().toString());
        r.setRequestTimestamp(Instant.now().toString()); r.setCapturedAt(captured); r.setLatitude(13.95); r.setLongitude(121.16);
        r.setFixValid(true); r.setSatellites(8); r.setHdop(1.2); r.setSpeed(20.0); return r;
    }
    @Test void capturesTrustedTimeAndRejectsStaleAndNoFixWithoutRestamping() {
        var device=terminal(); Instant captured=Instant.now().minusSeconds(4);
        assertThat(gps.receive(device,fix(device,captured)).get("status")).isEqualTo("GPS_VALID");
        var original=locations.findTopByPlateNumberOrderByRecordedAtDesc(device.plateNumber()).orElseThrow();
        assertThat(original.getShiftId()).isNull();
        assertThat(original.getCapturedAt()).isCloseTo(captured,org.assertj.core.api.Assertions.within(1,java.time.temporal.ChronoUnit.MILLIS));
        assertThat(original.getReceivedAt()).isAfter(original.getCapturedAt());
        assertThat(gps.receive(device,fix(device,Instant.now().minusSeconds(60))).get("status")).isEqualTo("GPS_STALE");
        var missing=fix(device,null); missing.setFixValid(false); missing.setLatitude(null); missing.setLongitude(null);
        assertThat(gps.receive(device,missing).get("status")).isEqualTo("GPS_NO_FIX");
        assertThat(locations.findTopByPlateNumberOrderByRecordedAtDesc(device.plateNumber()).orElseThrow().getId()).isEqualTo(original.getId());
        assertThat(observations.findTop100ByDeviceIdOrderByReceivedAtDesc(device.deviceId())).hasSize(3);
        assertThat(observations.findTop100ByDeviceIdOrderByReceivedAtDesc(device.deviceId()))
                .allSatisfy(row -> assertThat(row.getShiftId()).isNull());
    }
    @Test void invalidNumbersQualityFutureTimeAndJumpCannotCorruptHistory() {
        var device=terminal(); gps.receive(device,fix(device,Instant.now().minusSeconds(3)));
        var invalid=fix(device,Instant.now()); invalid.setLatitude(Double.NaN);
        assertThat(gps.receive(device,invalid).get("status")).isEqualTo("GPS_INVALID");
        var future=fix(device,Instant.now().plusSeconds(120));
        assertThat(gps.receive(device,future).get("status")).isEqualTo("GPS_INVALID");
        var poor=fix(device,Instant.now()); poor.setHdop(20.0);
        assertThat(gps.receive(device,poor).get("reason")).isEqualTo("LOW_QUALITY");
        var jump=fix(device,Instant.now()); jump.setLatitude(14.95);
        assertThat(gps.receive(device,jump).get("reason")).isEqualTo("IMPOSSIBLE_JUMP");
        assertThat(locations.findTopByPlateNumberOrderByRecordedAtDesc(device.plateNumber()).orElseThrow().getLatitude()).isEqualTo(13.95);
    }
    @Test void heartbeatIsThrottledAndCannotClaimPrinterReadiness() {
        var device=terminal(); var report=new DeviceHeartbeatRequest(); report.setFirmwareVersion("synthetic"); report.setPrinter(DeviceHeartbeatRequest.Peripheral.READY);
        report.setRequestNonce(UUID.randomUUID().toString()); report.setRequestTimestamp(Instant.now().toString());
        health.heartbeat(device,report); var first=devices.findById(device.id()).orElseThrow();
        assertThat(first.getHealthSnapshot()).contains("\"printer\":\"UNKNOWN\"");
        assertThatThrownBy(() -> health.heartbeat(device,report)).isInstanceOf(SecurityException.class);
        report.setRequestNonce(UUID.randomUUID().toString());
        health.heartbeat(device,report);
        assertThat(devices.findById(device.id()).orElseThrow().getHeartbeatAt()).isEqualTo(first.getHeartbeatAt());
    }
    @Test void authenticationTouchIsThrottledAndRotationInvalidatesInflightPrincipal() {
        var device=terminal(); auth.authenticate(device.deviceId(),device.deviceId());
        var seen=devices.findById(device.id()).orElseThrow().getLastSeenAt();
        auth.authenticate(device.deviceId(),device.deviceId());
        assertThat(devices.findById(device.id()).orElseThrow().getLastSeenAt()).isEqualTo(seen);
        auth.rotateToken(device.id());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> auth.lockPaymentDevice(device))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> auth.authenticate(device.deviceId(),device.deviceId())).isInstanceOf(SecurityException.class);
        auth.revoke(device.id());
        assertThatThrownBy(() -> gps.receive(device,fix(device,Instant.now()))).isInstanceOf(SecurityException.class);
    }
}
