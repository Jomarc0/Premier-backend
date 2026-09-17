package com.premier.controller;

import com.premier.model.Passenger;
import com.premier.payment.service.FcmDiagnostics;
import com.premier.payment.service.PaymentPushSender;
import com.premier.repository.PassengerFcmTokenRepository;
import com.premier.request.FcmTokenRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

/** Explicit opt-in on a development server; requires the current passenger's registered token. */
@RestController
@Profile("dev")
@ConditionalOnProperty(name = "firebase.diagnostics.enabled", havingValue = "true")
@RequestMapping("/api/passenger/notifications")
@RequiredArgsConstructor
@Slf4j
public class DevelopmentNotificationController {
    private final PassengerFcmTokenRepository tokens;
    private final PaymentPushSender push;

    @PostMapping("/test")
    public ResponseEntity<?> test(@AuthenticationPrincipal Passenger passenger,
            @Valid @RequestBody FcmTokenRequest request) {
        if (passenger == null) return ResponseEntity.status(401).build();
        boolean owned = tokens.findByFcmToken(request.getFcmToken())
                .map(t -> t.getPassenger().getId().equals(passenger.getId())).orElse(false);
        if (!owned) return ResponseEntity.status(409).body(Map.of("status", "CURRENT_DEVICE_NOT_REGISTERED"));
        String reference = "TEST-" + UUID.randomUUID();
        log.debug("[FCM] stage=SEND passenger={} reference={} kind=TEST", passenger.getId(), reference);
        try {
            String messageId = push.send(request.getFcmToken(), "TEST", reference, 10000);
            return ResponseEntity.ok(Map.of("status", "FCM_ACCEPTED", "messageId", messageId, "reference", reference));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[FCM] result=FAILED passenger={} reference={} httpStatus={} code={}",
                    passenger.getId(), reference, FcmDiagnostics.httpStatus(failure), FcmDiagnostics.code(failure));
            return ResponseEntity.status(502).body(Map.of("status", "FCM_FAILED", "reference", reference,
                    "code", FcmDiagnostics.code(failure), "httpStatus", FcmDiagnostics.httpStatus(failure)));
        }
    }
}
