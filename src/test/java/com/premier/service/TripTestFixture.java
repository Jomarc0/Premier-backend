package com.premier.service;

import com.premier.driver.model.Driver;
import com.premier.driver.model.DriverShift;
import com.premier.driver.model.DriverStatus;
import com.premier.driver.model.ShiftStatus;
import com.premier.driver.model.Vehicle;
import com.premier.driver.model.VehicleStatus;
import com.premier.driver.repository.DriverRepository;
import com.premier.driver.repository.DriverShiftRepository;
import com.premier.driver.repository.VehicleRepository;
import com.premier.trip.model.TripDirection;
import com.premier.trip.model.TripStatus;
import com.premier.trip.model.VehicleTrip;
import com.premier.trip.repository.VehicleTripRepository;

import java.time.LocalDateTime;

final class TripTestFixture {
    private TripTestFixture() {}

    static VehicleTrip activeTrip(String plate, VehicleRepository vehicles, DriverRepository drivers,
                                  DriverShiftRepository shifts, VehicleTripRepository trips) {
        Vehicle vehicle = vehicles.findByPlateNumber(plate).orElseGet(() -> vehicles.saveAndFlush(Vehicle.builder()
                .plateNumber(plate).totalCapacity(50).route("SM Terminal to Grand Terminal")
                .status(VehicleStatus.ACTIVE).build()));
        Driver driver = drivers.findByLicenseNumber("TEST-" + plate).orElseGet(() -> drivers.saveAndFlush(Driver.builder()
                .fullName("Synthetic Trip Driver").licenseNumber("TEST-" + plate).phoneNumber("0000000000")
                .status(DriverStatus.ACTIVE).build()));
        DriverShift shift = shifts.findByVehiclePlateNumberAndStatus(plate, ShiftStatus.ACTIVE)
                .orElseGet(() -> shifts.saveAndFlush(DriverShift.builder().driver(driver).vehicle(vehicle)
                        .status(ShiftStatus.ACTIVE).build()));
        return trips.findByVehicleIdAndStatus(vehicle.getId(), TripStatus.ACTIVE)
                .orElseGet(() -> trips.saveAndFlush(VehicleTrip.builder().vehicle(vehicle)
                        .vehiclePlateNumber(plate).driverShift(shift).direction(TripDirection.SM_TO_GRAND)
                        .originTerminal("SM Terminal").destinationTerminal("Grand Terminal")
                        .startedAt(LocalDateTime.now().minusDays(1)).status(TripStatus.ACTIVE).build()));
    }
}
