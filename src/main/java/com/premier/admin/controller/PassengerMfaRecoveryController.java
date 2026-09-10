package com.premier.admin.controller;

import com.premier.admin.model.Admin;
import com.premier.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/admin/support-tickets")
public class PassengerMfaRecoveryController {
    private final AuthService auth;
    public record RecoveryIntake(@NotBlank @Size(max=80) String cardNumber,
                                 @NotBlank @Email @Size(max=160) String email,
                                 @NotBlank @Size(min=10,max=500) String reason) {}
    @PostMapping("/recovery-intake")
    public ResponseEntity<?> intake(@AuthenticationPrincipal Admin admin, @Valid @RequestBody RecoveryIntake request) {
        return ResponseEntity.ok(auth.openRecoveryTicket(admin, request.cardNumber(), request.email(), request.reason()));
    }
    public record RecoveryAuthorization(@NotBlank @Size(min = 10, max = 240) String reason,
                                        @NotNull @AssertTrue Boolean identityVerified) {}
    @PostMapping("/{ticketId}/authorize-mfa-recovery")
    public ResponseEntity<?> authorize(@AuthenticationPrincipal Admin admin, @PathVariable Long ticketId,
                                       @Valid @RequestBody RecoveryAuthorization request) {
        return ResponseEntity.ok(auth.authorizeRecovery(admin, ticketId, request.reason(), Boolean.TRUE.equals(request.identityVerified())));
    }
}
