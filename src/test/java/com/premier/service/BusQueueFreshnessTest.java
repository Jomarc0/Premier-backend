package com.premier.service;

import com.premier.device.model.*;
import com.premier.device.repository.DeviceRepository;
import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import com.premier.staffqueue.model.BusQueueStatus;
import com.premier.staffqueue.service.BusQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusQueueFreshnessTest {
    @Test void dar5315SmLipaRouteIsIncomingToGrand() {
        var vehicles=mock(VehicleRepository.class); var locations=mock(DriverLocationRepository.class); var devices=mock(DeviceRepository.class);
        var vehicle=Vehicle.builder().plateNumber("DAR-5315").route("SM Lipa → Grand Terminal").status(VehicleStatus.ACTIVE).build();
        var location=location("DAR-5315", Instant.now(), 0.0);
        var device=activeDevice("DAR-5315", "GPS_VALID");
        stub(vehicles, locations, devices, vehicle, location, device);

        var dashboard=service(vehicles, locations, devices).getDashboard();

        assertThat(dashboard.incomingToSmTerminal()).isEmpty();
        assertThat(dashboard.incomingToGrandTerminal()).singleElement().satisfies(row -> {
            assertThat(row.plateNumber()).isEqualTo("DAR-5315");
            assertThat(row.routeDirection()).isEqualTo("SM Terminal to Grand Terminal");
            assertThat(row.distanceKm()).isEqualTo(18.1);
            assertThat(row.etaMinutes()).isNull();
            assertThat(row.status()).isEqualTo(BusQueueStatus.ON_ROUTE);
        });
    }

    @Test void reversedRouteIsIncomingToSm() {
        var vehicles=mock(VehicleRepository.class); var locations=mock(DriverLocationRepository.class); var devices=mock(DeviceRepository.class);
        var vehicle=Vehicle.builder().plateNumber("DAR-5315").route("Grand Terminal → SM Lipa").status(VehicleStatus.ACTIVE).build();
        var location=location("DAR-5315", Instant.now(), 30.0);
        stub(vehicles, locations, devices, vehicle, location, activeDevice("DAR-5315", "GPS_VALID"));

        var dashboard=service(vehicles, locations, devices).getDashboard();

        assertThat(dashboard.incomingToGrandTerminal()).isEmpty();
        assertThat(dashboard.incomingToSmTerminal()).singleElement().satisfies(row -> {
            assertThat(row.routeDirection()).isEqualTo("Grand Terminal to SM Terminal");
            assertThat(row.distanceKm()).isEqualTo(4.7);
        });
    }

    @Test void inactiveVehicleIsExcludedAndNoActiveTripIsRequired() {
        var vehicles=mock(VehicleRepository.class); var locations=mock(DriverLocationRepository.class); var devices=mock(DeviceRepository.class);
        when(vehicles.findByStatus(VehicleStatus.ACTIVE)).thenReturn(List.of());

        var dashboard=service(vehicles, locations, devices).getDashboard();

        assertThat(dashboard.incomingToSmTerminal()).isEmpty();
        assertThat(dashboard.incomingToGrandTerminal()).isEmpty();
        verifyNoInteractions(locations, devices);
    }

    @Test void invalidOrOfflineGpsKeepsRecognizedBusWithUnknownTelemetry() {
        var vehicles=mock(VehicleRepository.class); var locations=mock(DriverLocationRepository.class); var devices=mock(DeviceRepository.class);
        var vehicle=Vehicle.builder().plateNumber("DAR-5315").route("SM_LIPA to GRAND_TERMINAL").status(VehicleStatus.ACTIVE).build();
        var location=location("DAR-5315", Instant.now(), 20.0);
        var device=activeDevice("DAR-5315", "GPS_INVALID");
        stub(vehicles, locations, devices, vehicle, location, device);

        var invalid=service(vehicles, locations, devices).getDashboard().incomingToGrandTerminal().get(0);
        assertThat(invalid.status()).isEqualTo(BusQueueStatus.GPS_UNKNOWN);
        assertThat(invalid.distanceKm()).isNull();

        device.setGpsState("GPS_VALID");
        device.setStatus(DeviceStatus.INACTIVE);
        var offline=service(vehicles, locations, devices).getDashboard().incomingToGrandTerminal().get(0);
        assertThat(offline.status()).isEqualTo(BusQueueStatus.GPS_UNKNOWN);
        assertThat(offline.capturedAt()).isNull();
    }

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

    private DriverLocation location(String plate, Instant capturedAt, double speed) {
        return DriverLocation.builder().plateNumber(plate).deviceId("synthetic").latitude(13.94272).longitude(121.12092)
                .capturedAt(capturedAt).speed(speed).build();
    }

    private Device activeDevice(String plate, String gpsState) {
        return Device.builder().deviceId("synthetic").plateNumber(plate).gpsState(gpsState).status(DeviceStatus.ACTIVE).build();
    }

    private void stub(VehicleRepository vehicles, DriverLocationRepository locations, DeviceRepository devices,
                      Vehicle vehicle, DriverLocation location, Device device) {
        when(vehicles.findByStatus(VehicleStatus.ACTIVE)).thenReturn(List.of(vehicle));
        when(locations.findLatestForPlates(anyCollection())).thenReturn(List.of(location));
        when(devices.findByDeviceIdIn(anyCollection())).thenReturn(List.of(device));
    }

    private BusQueueService service(VehicleRepository vehicles, DriverLocationRepository locations, DeviceRepository devices) {
        var service=new BusQueueService(vehicles, locations, devices);
        ReflectionTestUtils.setField(service, "smTerminalLatitude", 13.954781);
        ReflectionTestUtils.setField(service, "smTerminalLongitude", 121.163096);
        ReflectionTestUtils.setField(service, "grandTerminalLatitude", 13.790391);
        ReflectionTestUtils.setField(service, "grandTerminalLongitude", 121.062721);
        return service;
    }
}
