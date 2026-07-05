package com.premier.cardfreeze.controller;

import com.premier.admin.model.Admin;
import com.premier.cardfreeze.request.CardFreezeReviewRequest;
import com.premier.cardfreeze.request.CardFreezeSubmitRequest;
import com.premier.cardfreeze.service.CardFreezeRequestService;
import com.premier.model.Passenger;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class CardFreezeRequestController {

    private final CardFreezeRequestService service;

    @PostMapping("/api/passenger/card-freeze-requests")
    public ResponseEntity<?> submit(@AuthenticationPrincipal Passenger passenger,
                                    @Valid @RequestBody CardFreezeSubmitRequest request) {
        return ResponseEntity.ok(service.submit(passenger, request.getRequestType(), request.getReason()));
    }

    @GetMapping("/api/passenger/card-freeze-requests")
    public ResponseEntity<?> passengerRequests(@AuthenticationPrincipal Passenger passenger) {
        return ResponseEntity.ok(service.passengerRequests(passenger));
    }

    @GetMapping("/api/admin/card-freeze-requests")
    public ResponseEntity<?> adminRequests() {
        return ResponseEntity.ok(service.adminRequests());
    }

    @GetMapping("/api/admin/card-freeze-requests/{id}")
    public ResponseEntity<?> adminRequest(@PathVariable Long id) {
        return ResponseEntity.ok(service.adminRequest(id));
    }

    @PutMapping("/api/admin/card-freeze-requests/{id}/approve")
    public ResponseEntity<?> approve(@AuthenticationPrincipal Admin admin,
                                     @PathVariable Long id) {
        return ResponseEntity.ok(service.approve(admin, id));
    }

    @PutMapping("/api/admin/card-freeze-requests/{id}/reject")
    public ResponseEntity<?> reject(@AuthenticationPrincipal Admin admin,
                                    @PathVariable Long id,
                                    @Valid @RequestBody CardFreezeReviewRequest request) {
        return ResponseEntity.ok(service.reject(admin, id, request.getAdminRemarks()));
    }
}
