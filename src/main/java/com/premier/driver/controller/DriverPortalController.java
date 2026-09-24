package com.premier.driver.controller;

import com.premier.driver.request.DriverLoginRequest;
import com.premier.driver.request.LocationRequest;
import com.premier.driver.security.DriverPrincipal;
import com.premier.driver.service.DriverPortalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/driver")
@RequiredArgsConstructor
public class DriverPortalController {
    private final DriverPortalService service;

    @PostMapping("/login")
    public com.premier.response.ApiResponse<com.premier.driver.response.DriverLoginResponse> login(@Valid @RequestBody DriverLoginRequest request) {
        return service.login(request);
    }

    @GetMapping("/shift/{plateNumber}")
    public com.premier.response.ApiResponse<com.premier.driver.response.DriverShiftResponse> shift(Authentication authentication,
            @PathVariable String plateNumber) {
        return service.currentShift(principal(authentication), plateNumber);
    }

    @PostMapping("/location")
    public com.premier.response.ApiResponse<java.util.Map<String, Object>> location(Authentication authentication,
            @Valid @RequestBody LocationRequest request) {
        return service.location(principal(authentication), request);
    }

    @PostMapping("/end-shift/{plateNumber}")
    public com.premier.response.ApiResponse<String> endShift(Authentication authentication, @PathVariable String plateNumber) {
        return service.endShift(principal(authentication), plateNumber);
    }

    @PostMapping("/trips/start")
    public com.premier.response.ApiResponse<com.premier.trip.response.VehicleTripResponse> startTrip(
            Authentication authentication,
            @Valid @RequestBody com.premier.trip.request.StartTripRequest request) {
        return service.startTrip(principal(authentication), request.direction());
    }

    @PostMapping("/trips/{tripId}/complete")
    public com.premier.response.ApiResponse<com.premier.trip.response.VehicleTripResponse> completeTrip(
            Authentication authentication, @PathVariable Long tripId) {
        return service.completeTrip(principal(authentication), tripId);
    }

    @PostMapping("/drop-off/{onboardId}")
    public com.premier.response.ApiResponse<String> dropOff(Authentication authentication, @PathVariable Long onboardId) {
        return service.dropOff(principal(authentication), onboardId);
    }

    private DriverPrincipal principal(Authentication authentication) {
        return authentication == null ? null : (DriverPrincipal) authentication.getPrincipal();
    }
}
