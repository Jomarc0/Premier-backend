package com.premier.support.controller;

import com.premier.support.request.PublicSupportTicketRequest;
import com.premier.support.service.SupportTicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/support-tickets")
@RequiredArgsConstructor
public class PublicSupportTicketController {

    private final SupportTicketService supportTicketService;

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody PublicSupportTicketRequest request) {
        return ResponseEntity.ok(supportTicketService.createPublicTicket(
                request.getCardNumber(),
                request.getEmail(),
                request.getIssueType(),
                request.getReason()));
    }
}
