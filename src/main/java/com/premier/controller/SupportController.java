package com.premier.controller;

import com.premier.model.Passenger;
import com.premier.request.SupportTicketRequest;
import com.premier.request.TicketReplyRequest;
import com.premier.service.SupportService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/passenger/support")
@RequiredArgsConstructor
public class SupportController {

    private final SupportService supportService;

    @PostMapping("/ticket")
    public ResponseEntity<?> createTicket(
            @AuthenticationPrincipal Passenger passenger,
            @Valid @RequestBody SupportTicketRequest request) {
        return ResponseEntity.ok(supportService.createTicket(passenger, request));
    }

    @GetMapping("/tickets")
    public ResponseEntity<?> getMyTickets(@AuthenticationPrincipal Passenger passenger) {
        return ResponseEntity.ok(supportService.getMyTickets(passenger));
    }

    @GetMapping("/tickets/{ticketId}")
    public ResponseEntity<?> getTicket(
            @AuthenticationPrincipal Passenger passenger,
            @PathVariable Long ticketId) {
        return ResponseEntity.ok(supportService.getTicketById(passenger, ticketId));
    }

    @PostMapping("/tickets/{ticketId}/reply")
    public ResponseEntity<?> reply(
            @AuthenticationPrincipal Passenger passenger,
            @PathVariable Long ticketId,
            @Valid @RequestBody TicketReplyRequest request) {
        return ResponseEntity.ok(supportService.replyToTicket(passenger, ticketId, request));
    }
}