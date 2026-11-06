package com.premier.admin.controller;

import com.premier.admin.report.FareTransactionReportService;
import com.premier.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/fare-reports")
@RequiredArgsConstructor
public class AdminFareTransactionReportController {
    private final FareTransactionReportService reports;

    @GetMapping("/vehicles")
    public ResponseEntity<?> vehicles() {
        return ResponseEntity.ok(ApiResponse.success("Report buses fetched.", reports.getVehicles()));
    }

    @GetMapping
    public ResponseEntity<?> report(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate,
            @RequestParam(required = false) Long vehicleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        try {
            return ResponseEntity.ok(ApiResponse.success("Fare transaction report fetched.",
                    reports.getReport(startDate, endDate, vehicleId, page, size)));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(ApiResponse.error(invalid.getMessage()));
        }
    }
}
