package com.premier.staffcash.controller;

import com.premier.admin.model.Admin;
import com.premier.response.ApiResponse;
import com.premier.staffcash.model.StaffCashCardStatus;
import com.premier.staffcash.request.RegisterStaffCashCardRequest;
import com.premier.staffcash.service.StaffCashFareService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/staff-cash-cards")
@RequiredArgsConstructor
public class AdminStaffCashCardController {
    private final StaffCashFareService service;

    @GetMapping
    public ResponseEntity<?> list() { return ResponseEntity.ok(service.listCards()); }

    @PostMapping
    public ResponseEntity<?> register(Authentication authentication,
                                      @Valid @RequestBody RegisterStaffCashCardRequest request) {
        return ResponseEntity.ok(service.register((Admin) authentication.getPrincipal(), request));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> status(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(service.changeStatus(id,
                    StaffCashCardStatus.valueOf(body.getOrDefault("status", "").toUpperCase())));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid staff cash card status."));
        }
    }
}
