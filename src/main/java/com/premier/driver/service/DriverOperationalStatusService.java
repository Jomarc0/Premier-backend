package com.premier.driver.service;

import com.premier.driver.model.DriverLocation;
import com.premier.driver.model.DriverStatus;
import com.premier.driver.model.DriverShift;
import com.premier.driver.model.ShiftStatus;
import com.premier.driver.model.VehicleStatus;
import com.premier.driver.repository.DriverLocationRepository;
import com.premier.driver.repository.DriverShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class DriverOperationalStatusService {

    private static final long ACTIVE_TIMEOUT_MINUTES = 10;

    private final DriverShiftRepository shiftRepository;
    private final DriverLocationRepository locationRepository;

    @Transactional
    @Scheduled(fixedDelay = 60_000)
    public void reconcileStaleOperations() {
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(ACTIVE_TIMEOUT_MINUTES);

        for (DriverShift shift : shiftRepository.findByStatus(ShiftStatus.ACTIVE)) {
            LocalDateTime lastActivity = lastActivityFor(shift);
            if (lastActivity == null || !lastActivity.isBefore(staleBefore)) {
                continue;
            }

            shift.setStatus(ShiftStatus.COMPLETED);
            shift.setShiftEnd(lastActivity);
            shiftRepository.save(shift);

            if (shift.getDriver() != null) {
                shift.getDriver().setStatus(DriverStatus.INACTIVE);
            }
            if (shift.getVehicle() != null) {
                shift.getVehicle().setStatus(VehicleStatus.INACTIVE);
            }

            log.info("Closed stale driver shift id={} after last activity at {}",
                    shift.getId(), lastActivity);
        }
    }

    private LocalDateTime lastActivityFor(DriverShift shift) {
        LocalDateTime latest = latest(shift.getLastLocationUpdate(), shift.getShiftStart());
        if (shift.getVehicle() == null || shift.getVehicle().getPlateNumber() == null) {
            return latest;
        }

        return latest(locationRepository
                .findTopByPlateNumberOrderByRecordedAtDesc(shift.getVehicle().getPlateNumber())
                .map(DriverLocation::getRecordedAt)
                .orElse(null), latest);
    }

    private LocalDateTime latest(LocalDateTime first, LocalDateTime second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }
}
