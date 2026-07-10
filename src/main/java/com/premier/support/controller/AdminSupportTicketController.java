package com.premier.support.controller;

import com.premier.admin.model.Admin;
import com.premier.support.request.ReplaceRfidUidRequest;
import com.premier.support.request.SupportTicketDecisionRequest;
import com.premier.support.request.SupportTicketNotesRequest;
import com.premier.support.request.SupportTicketStatusRequest;
import com.premier.support.service.SupportTicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/support-tickets")
@RequiredArgsConstructor
public class AdminSupportTicketController {

    private final SupportTicketService supportTicketService;

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(supportTicketService.listTickets());
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary() {
        return ResponseEntity.ok(supportTicketService.summary());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ResponseEntity.ok(supportTicketService.getTicket(id));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> status(@AuthenticationPrincipal Admin admin,
                                    @PathVariable Long id,
                                    @Valid @RequestBody SupportTicketStatusRequest request) {
        return ResponseEntity.ok(supportTicketService.updateStatus(admin, id, request.getStatus()));
    }

    @PutMapping("/{id}/notes")
    public ResponseEntity<?> notes(@AuthenticationPrincipal Admin admin,
                                   @PathVariable Long id,
                                   @Valid @RequestBody SupportTicketNotesRequest request) {
        return ResponseEntity.ok(supportTicketService.updateNotes(admin, id, request.getAdminNotes()));
    }

    @PostMapping("/{id}/freeze-card")
    public ResponseEntity<?> freezeCard(@AuthenticationPrincipal Admin admin,
                                        @PathVariable Long id,
                                        @RequestBody(required = false) SupportTicketDecisionRequest request) {
        return ResponseEntity.ok(supportTicketService.freezeCard(admin, id,
                request != null ? request.getAdminNotes() : null));
    }

    @PostMapping("/{id}/replace-rfid")
    public ResponseEntity<?> replaceRfid(@AuthenticationPrincipal Admin admin,
                                         @PathVariable Long id,
                                         @Valid @RequestBody ReplaceRfidUidRequest request) {
        return ResponseEntity.ok(supportTicketService.replaceRfidUid(admin, id,
                request.getNewRfidUid(), request.getAdminNotes()));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<?> resolve(@AuthenticationPrincipal Admin admin,
                                     @PathVariable Long id,
                                     @RequestBody(required = false) SupportTicketDecisionRequest request) {
        return ResponseEntity.ok(supportTicketService.resolve(admin, id,
                request != null ? request.getAdminNotes() : null));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@AuthenticationPrincipal Admin admin,
                                    @PathVariable Long id,
                                    @RequestBody(required = false) SupportTicketDecisionRequest request) {
        return ResponseEntity.ok(supportTicketService.reject(admin, id,
                request != null ? request.getAdminNotes() : null));
    }
}
