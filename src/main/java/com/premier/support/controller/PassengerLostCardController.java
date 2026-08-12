package com.premier.support.controller;

import com.premier.model.Passenger;
import com.premier.support.request.LostCardReportRequest;
import com.premier.support.service.SupportTicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A full passenger JWT is required; this endpoint is never public. */
@RestController
@RequestMapping("/api/passenger/card")
@RequiredArgsConstructor
public class PassengerLostCardController {
    private final SupportTicketService supportTicketService;

    @PostMapping("/report-lost")
    public ResponseEntity<?> reportLost(@AuthenticationPrincipal Passenger passenger,
                                        @Valid @org.springframework.web.bind.annotation.RequestBody LostCardReportRequest request) {
        return ResponseEntity.ok(supportTicketService.reportLostCard(passenger, request.getEmail()));
    }
}
