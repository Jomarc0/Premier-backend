package com.premier.controller;

import com.premier.request.FcmTokenRequest;
import com.premier.model.Passenger;
import com.premier.service.FirebaseService;
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

    // Save FCM token when passenger logs in
    @PutMapping("/fcm-token")
    public ResponseEntity<?> updateFcmToken(
            @AuthenticationPrincipal Passenger passenger,
            @Valid @RequestBody FcmTokenRequest request) {
        return ResponseEntity.ok(
            firebaseService.updateFcmToken(
                passenger, request));
    }

    @PostMapping("/test")
    public ResponseEntity<?> testNotification(
            @AuthenticationPrincipal Passenger passenger) {
        if (passenger.getFcmToken() != null) {
            firebaseService.sendNotification(
                passenger.getFcmToken(),
                "Test Notification 🔔",
                "This is a test from Premier Transit!"
            );
            return ResponseEntity.ok(
                "Notification sent!");
        }
        return ResponseEntity.ok(
            "No FCM token registered.");
    }
}