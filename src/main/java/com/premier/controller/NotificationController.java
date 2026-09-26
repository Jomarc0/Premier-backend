package com.premier.controller;

import com.premier.request.FcmTokenRequest;
import com.premier.model.Passenger;
import com.premier.service.FirebaseService;
import com.premier.payment.service.PaymentNotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/passenger/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final FirebaseService firebaseService;
    private final PaymentNotificationService paymentNotificationService;

    @GetMapping
    public ResponseEntity<?> history(
            @AuthenticationPrincipal Passenger passenger,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (passenger == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(paymentNotificationService.history(passenger, page, size));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<?> markRead(
            @AuthenticationPrincipal Passenger passenger,
            @PathVariable Long id) {
        if (passenger == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(paymentNotificationService.markRead(passenger, id));
    }

    @PutMapping("/read-all")
    public ResponseEntity<?> markAllRead(@AuthenticationPrincipal Passenger passenger) {
        if (passenger == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(paymentNotificationService.markAllRead(passenger));
    }

    // Save FCM token when passenger logs in
    @PutMapping("/fcm-token")
    public ResponseEntity<?> updateFcmToken(
            @AuthenticationPrincipal Passenger passenger,
            @Valid @RequestBody FcmTokenRequest request) {
        if (passenger == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(
            firebaseService.updateFcmToken(
                passenger, request));
    }

    @DeleteMapping("/fcm-token")
    public ResponseEntity<?> removeFcmToken(@AuthenticationPrincipal Passenger passenger,
            @Valid @RequestBody FcmTokenRequest request) {
        if (passenger == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(firebaseService.removeFcmToken(passenger, request));
    }
}
