package com.premier.admin.controller;

import com.premier.device.repository.DeviceRepository;
import com.premier.driver.model.DriverLocation;
import com.premier.driver.model.Vehicle;
import com.premier.driver.model.VehicleStatus;
import com.premier.driver.repository.DriverLocationRepository;
import com.premier.driver.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminVehicleMonitoringControllerTest {
    @Mock VehicleRepository vehicleRepository;
    @Mock DriverLocationRepository locationRepository;
    @Mock DeviceRepository deviceRepository;

    private AdminVehicleMonitoringController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminVehicleMonitoringController(vehicleRepository, locationRepository, deviceRepository);
        ReflectionTestUtils.setField(controller, "onlineMinutes", 5L);
        ReflectionTestUtils.setField(controller, "offlineMinutes", 15L);
        ReflectionTestUtils.setField(controller, "pointALatitude", 13.954781);
        ReflectionTestUtils.setField(controller, "pointALongitude", 121.163096);
        ReflectionTestUtils.setField(controller, "pointBLatitude", 13.790391);
        ReflectionTestUtils.setField(controller, "pointBLongitude", 121.062721);
    }

    @Test
    void busWithoutLatestValidFixHasNoCoordinatesAndIsOffline() {
        Vehicle bus = bus();
        when(vehicleRepository.findAll()).thenReturn(List.of(bus));
        when(locationRepository.findLatestValidPerPlate()).thenReturn(List.of());
        when(deviceRepository.findAll()).thenReturn(List.of());

        Map<String, Object> data = controller.buses().getData();
        Map<?, ?> row = (Map<?, ?>) ((List<?>) data.get("buses")).get(0);

        assertThat(row.get("hasValidLocation")).isEqualTo(false);
        assertThat(row.get("latitude")).isNull();
        assertThat(row.get("longitude")).isNull();
        assertThat(row.get("status")).isEqualTo("OFFLINE");
        assertThat(row.get("route")).isEqualTo("SM Lipa → Grand Terminal");
    }

    @Test
    void historyDropsZeroCoordinatesAndReturnsChronologicalSummary() {
        Vehicle bus = bus();
        LocalDate date = LocalDate.now();
        DriverLocation valid = DriverLocation.builder().id(1L).plateNumber("DAR-5315")
                .latitude(13.94).longitude(121.12).speed(18.0).recordedAt(date.atTime(8, 5)).build();
        DriverLocation zero = DriverLocation.builder().id(2L).plateNumber("DAR-5315")
                .latitude(0.0).longitude(0.0).speed(0.0).recordedAt(date.atTime(8, 10)).build();
        when(vehicleRepository.findByPlateNumber("DAR-5315")).thenReturn(Optional.of(bus));
        when(locationRepository.findByPlateNumberAndRecordedAtBetweenOrderByRecordedAtAsc(
                eq("DAR-5315"), any(), any())).thenReturn(List.of(valid, zero));

        Map<String, Object> data = controller.locationHistory(
                "dar-5315", date, LocalTime.of(8, 0), LocalTime.of(9, 0), null).getData();
        List<?> history = (List<?>) data.get("history");
        Map<?, ?> summary = (Map<?, ?>) data.get("summary");

        assertThat(history).hasSize(1);
        assertThat(summary.get("gpsRecords")).isEqualTo(1);
        assertThat(((Map<?, ?>) history.get(0)).get("status")).isEqualTo("Moving");
    }

    @Test
    void busWithStaleButRecentGpsFixIsDelayed() {
        Vehicle bus = bus();
        DriverLocation location = DriverLocation.builder().id(3L).plateNumber("DAR-5315")
                .latitude(13.90).longitude(121.10).speed(12.0)
                .recordedAt(LocalDateTime.now().minusMinutes(10)).build();
        when(vehicleRepository.findAll()).thenReturn(List.of(bus));
        when(locationRepository.findLatestValidPerPlate()).thenReturn(List.of(location));
        when(deviceRepository.findAll()).thenReturn(List.of());

        Map<String, Object> data = controller.buses().getData();
        Map<?, ?> row = (Map<?, ?>) ((List<?>) data.get("buses")).get(0);

        assertThat(row.get("hasValidLocation")).isEqualTo(true);
        assertThat(row.get("status")).isEqualTo("DELAYED");
    }

    private Vehicle bus() {
        return Vehicle.builder().id(7L).plateNumber("DAR-5315").totalCapacity(50)
                .route("legacy route value").status(VehicleStatus.ACTIVE).build();
    }
}
