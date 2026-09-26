package com.premier.staffqueue.controller;

import com.premier.response.ApiResponse;
import com.premier.staffqueue.response.BusQueueDashboardResponse;
import com.premier.staffqueue.response.BusQueueItemResponse;
import com.premier.staffqueue.response.EligibleQueueVehicleResponse;
import com.premier.staffqueue.request.ManualQueueCheckInRequest;
import com.premier.staffqueue.service.BusQueueService;
import com.premier.admin.model.Admin;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/staff/bus-queue")
@RequiredArgsConstructor
public class BusQueueController {

    private final BusQueueService busQueueService;

    @GetMapping
    public ResponseEntity<ApiResponse<BusQueueDashboardResponse>> getDashboard() {
        return ResponseEntity.ok(ApiResponse.success(
                "Staff bus queue loaded.",
                busQueueService.getDashboard()
        ));
    }

    @GetMapping("/eligible-vehicles")
    public ResponseEntity<ApiResponse<List<EligibleQueueVehicleResponse>>> eligibleVehicles() {
        return ResponseEntity.ok(ApiResponse.success("Eligible queue vehicles loaded.",
                busQueueService.eligibleVehicles()));
    }

    @PostMapping("/check-in")
    public ResponseEntity<ApiResponse<BusQueueItemResponse>> manualCheckIn(
            @Valid @RequestBody ManualQueueCheckInRequest request, Authentication authentication) {
        Admin staff = authentication != null && authentication.getPrincipal() instanceof Admin admin ? admin : null;
        return ResponseEntity.ok(ApiResponse.success("Vehicle checked in.",
                busQueueService.manualCheckIn(request.terminal(), request.vehicleId(), staff)));
    }

    @PostMapping("/{id}/boarding")
    public ResponseEntity<ApiResponse<BusQueueItemResponse>> startBoarding(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Boarding started.", busQueueService.startBoarding(id)));
    }

    @PostMapping("/{id}/depart")
    public ResponseEntity<ApiResponse<BusQueueItemResponse>> depart(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Vehicle marked departed.", busQueueService.markDeparted(id)));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<BusQueueItemResponse>> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Queue entry cancelled.", busQueueService.cancel(id)));
    }
}
