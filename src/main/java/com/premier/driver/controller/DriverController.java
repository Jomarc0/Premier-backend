package com.premier.driver.controller;

import com.premier.driver.request.LocationRequest;
import com.premier.driver.service.DriverService;
import com.premier.driver.service.LocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/driver")
@RequiredArgsConstructor
public class DriverController {

    private final DriverService   driverService;
    private final LocationService locationService;

    // LOGIn
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(
                driverService.driverLogin(
                        body.get("plateNumber")));
    }

    //SHIFT INFO
    @GetMapping("/shift/{plateNumber}")
    public ResponseEntity<?> getShiftInfo(
            @PathVariable String plateNumber) {
        return ResponseEntity.ok(
                driverService.getShiftInfo(plateNumber));
    }

    // PASSENGER TAP-IN 
    @PostMapping("/tap-in")
    public ResponseEntity<?> tapIn(
            @RequestBody Map<String, Object> body) {
        Long shiftId = Long.parseLong(
                body.get("shiftId").toString());
        String rfidUid = (String) body.get("rfidUid");
        String dropOff = (String) body.get("dropOffLocation");
        int count = body.get("passengerCount") != null
                ? Integer.parseInt(
                    body.get("passengerCount").toString()) : 1;
        return ResponseEntity.ok(
                driverService.tapIn(
                        shiftId, rfidUid, dropOff, null, count));
    }

    // CONFIRM DROP-OFF 
    @PostMapping("/drop-off/{onboardId}")
    public ResponseEntity<?> dropOff(
            @PathVariable Long onboardId) {
        return ResponseEntity.ok(
                driverService.dropOff(onboardId));
    }

    // END SHIFT
    @PostMapping("/end-shift/{plateNumber}")
    public ResponseEntity<?> endShift(
            @PathVariable String plateNumber) {
        return ResponseEntity.ok(
                driverService.endShift(plateNumber));
    }

    // UPDATE GPS 
    @PutMapping("/gps")
    public ResponseEntity<?> updateGps(
            @RequestBody Map<String, Object> body) {
        String plateNumber = (String) body.get("plateNumber");
        Double lat = Double.parseDouble(
                body.get("latitude").toString());
        Double lng = Double.parseDouble(
                body.get("longitude").toString());
        return ResponseEntity.ok(
                driverService.updateGps(plateNumber, lat, lng));
    }

    //LIST HELPERS 
    @GetMapping("/vehicles")
    public ResponseEntity<?> getAllVehicles() {
        return ResponseEntity.ok(driverService.getAllVehicles());
    }

    @GetMapping("/drivers")
    public ResponseEntity<?> getAllDrivers() {
        return ResponseEntity.ok(driverService.getAllDrivers());
    }

    @GetMapping("/buses")
    public ResponseEntity<?> getBusLocations() {
        return ResponseEntity.ok(driverService.getBusLocations());
    }

    // GPS TRACKING 

    /**
     * POST 
     * Called every 5 s by useGpsTracking hook.
     * Updates vehicle coords, saves history, broadcasts WS.
     */
    @PostMapping("/location")
    public ResponseEntity<?> updateLocation(
            @Valid @RequestBody LocationRequest req) {
        return ResponseEntity.ok(
                locationService.updateLocation(req));
    }


     //Latest GPS ping per bus — admin live map.

    @GetMapping("/live-locations")
    public ResponseEntity<?> getLiveLocations() {
        return ResponseEntity.ok(
                locationService.getLiveLocations());
    }

 
     // Full GPS trail for a shift — route replay.
    @GetMapping("/shift-history/{shiftId}")
    public ResponseEntity<?> getShiftHistory(
            @PathVariable Long shiftId) {
        return ResponseEntity.ok(
                locationService.getShiftHistory(shiftId));
    }

    @GetMapping("/location-history/{plateNumber}")
    public ResponseEntity<?> getLocationHistory(
            @PathVariable String plateNumber,
            @RequestParam(defaultValue = "day") String range) {
        return ResponseEntity.ok(
                locationService.getPlateHistory(plateNumber, range));
    }
}
