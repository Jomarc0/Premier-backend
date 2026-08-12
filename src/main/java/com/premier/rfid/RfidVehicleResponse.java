package com.premier.rfid;

import com.premier.driver.model.Vehicle;
import com.premier.driver.model.VehicleStatus;
import lombok.Builder;
import lombok.Value;

/** Minimal terminal selection data; never serialize the Vehicle entity from a public route. */
@Value
@Builder
public class RfidVehicleResponse {
    Long id;
    String plateNumber;
    VehicleStatus status;

    static RfidVehicleResponse from(Vehicle vehicle) {
        return builder().id(vehicle.getId()).plateNumber(vehicle.getPlateNumber()).status(vehicle.getStatus()).build();
    }
}


