package com.premier.staffcash.controller;

import com.premier.admin.model.Admin;
import com.premier.staffcash.request.ConfirmStaffRemittanceRequest;
import com.premier.staffcash.service.AdminStaffCashService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController @RequestMapping("/api/admin/staff-cash") @RequiredArgsConstructor
public class AdminStaffCashController {
    private final AdminStaffCashService service;
    @GetMapping("/collections")
    public ResponseEntity<?> collections(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) { return ResponseEntity.ok(service.summaries(date)); }
    @GetMapping("/collections/{staffId}")
    public ResponseEntity<?> detail(@PathVariable Long staffId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) { return ResponseEntity.ok(service.detail(staffId, date)); }
    @GetMapping("/transactions")
    public ResponseEntity<?> transactions(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) { return ResponseEntity.ok(service.transactions(date)); }
    @PostMapping("/collections/{staffId}/remittance")
    public ResponseEntity<?> remit(Authentication auth, @PathVariable Long staffId, @Valid @RequestBody ConfirmStaffRemittanceRequest request) {
        return ResponseEntity.ok(service.confirm((Admin) auth.getPrincipal(), staffId, request.getDate(), request.getActualCashReceived()));
    }
}
