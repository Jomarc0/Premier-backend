package com.premier.staffqueue.controller;

import com.premier.response.ApiResponse;
import com.premier.staffqueue.response.BusQueueDashboardResponse;
import com.premier.staffqueue.service.BusQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
