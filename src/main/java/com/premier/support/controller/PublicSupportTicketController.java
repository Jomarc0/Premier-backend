package com.premier.support.controller;

import com.premier.model.Passenger;
import com.premier.support.request.PublicSupportTicketRequest;
import com.premier.support.service.SupportTicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/passenger/support-tickets")
@RequiredArgsConstructor
public class PublicSupportTicketController {

    private final SupportTicketService supportTicketService;

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Passenger passenger,
                                    @Valid @RequestBody PublicSupportTicketRequest request) {
        return ResponseEntity.ok(supportTicketService.createPassengerTicket(passenger,
                request.getEmail(),
                request.getIssueType(),
                request.getReason()));
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal Passenger passenger) {
        return ResponseEntity.ok(supportTicketService.listPassengerTickets(passenger));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@AuthenticationPrincipal Passenger passenger, @PathVariable Long id) {
        return ResponseEntity.ok(supportTicketService.getPassengerTicket(passenger, id));
    }
}
