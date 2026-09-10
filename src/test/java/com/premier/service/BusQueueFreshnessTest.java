package com.premier.service;

import com.premier.device.model.*;
import com.premier.device.repository.DeviceRepository;
import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import com.premier.staffqueue.model.BusQueueStatus;
import com.premier.staffqueue.service.BusQueueService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusQueueFreshnessTest {
    @Test void heartbeatWithoutGpsFixNeverMakesLastPositionCurrent() {
        var vehicles=mock(VehicleRepository.class); var locations=mock(DriverLocationRepository.class); var devices=mock(DeviceRepository.class);
        var vehicle=Vehicle.builder().plateNumber("SYN-001").route("SM Terminal to Grand Terminal").status(VehicleStatus.ACTIVE).build();
        var location=DriverLocation.builder().plateNumber("SYN-001").deviceId("synthetic").latitude(13.9).longitude(121.1)
                .capturedAt(Instant.now()).speed(40.0).build();
        var device=Device.builder().deviceId("synthetic").plateNumber("SYN-001").gpsState("GPS_NO_FIX").build();
        when(vehicles.findByStatus(VehicleStatus.ACTIVE)).thenReturn(List.of(vehicle));
        when(locations.findLatestForPlates(anyCollection())).thenReturn(List.of(location));
        when(devices.findByDeviceIdIn(anyCollection())).thenReturn(List.of(device));
        var service=new BusQueueService(vehicles,locations,devices);
        var row=service.getDashboard().incomingToGrandTerminal().get(0);
        assertThat(row.status()).isEqualTo(BusQueueStatus.GPS_UNKNOWN);
        assertThat(row.distanceKm()).isNull(); assertThat(row.etaMinutes()).isNull(); assertThat(row.capturedAt()).isNull();
        verify(locations,never()).findTopByPlateNumberOrderByRecordedAtDesc(anyString());
        verify(locations,times(1)).findLatestForPlates(anyCollection());
    }
    @Test void staleCaptureAndStoppedVehicleNeverReceiveFabricatedEta() {
        var vehicles=mock(VehicleRepository.class); var locations=mock(DriverLocationRepository.class); var devices=mock(DeviceRepository.class);
        var vehicle=Vehicle.builder().plateNumber("SYN-002").route("SM Terminal to Grand Terminal").status(VehicleStatus.ACTIVE).build();
        var location=DriverLocation.builder().plateNumber("SYN-002").deviceId("synthetic").latitude(13.9).longitude(121.1)
                .capturedAt(Instant.now().minusSeconds(60)).speed(40.0).build();
        var device=Device.builder().deviceId("synthetic").plateNumber("SYN-002").gpsState("GPS_VALID").build();
        when(vehicles.findByStatus(VehicleStatus.ACTIVE)).thenReturn(List.of(vehicle));
        when(locations.findLatestForPlates(anyCollection())).thenReturn(List.of(location));
        when(devices.findByDeviceIdIn(anyCollection())).thenReturn(List.of(device));
        var service=new BusQueueService(vehicles,locations,devices);
        assertThat(service.getDashboard().incomingToGrandTerminal().get(0).status()).isEqualTo(BusQueueStatus.GPS_UNKNOWN);
        location.setCapturedAt(Instant.now()); location.setSpeed(0.0);
        var fresh=service.getDashboard().incomingToGrandTerminal().get(0);
        assertThat(fresh.distanceKm()).isNotNull(); assertThat(fresh.etaMinutes()).isNull();
        device.setStatus(DeviceStatus.REVOKED);
        assertThat(service.getDashboard().incomingToGrandTerminal().get(0).status()).isEqualTo(BusQueueStatus.GPS_UNKNOWN);
    }
}
