package com.premier.controller;

import com.premier.model.Passenger;
import com.premier.response.ApiResponse;
import com.premier.service.FarePaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/passenger/fare")
@RequiredArgsConstructor
public class FareController {

    private final FarePaymentService farePaymentService;

    @PostMapping("/qr")
    public ResponseEntity<?> generateQr(@AuthenticationPrincipal Passenger passenger) {
        if (passenger == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized - please login again"));
        }

        try {
            return ResponseEntity.ok(farePaymentService.generateQrToken(passenger));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/qr/status")
    public ResponseEntity<?> getQrStatus(
            @AuthenticationPrincipal Passenger passenger,
            @RequestBody Map<String, String> body) {
        if (passenger == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized - please login again"));
        }

        try {
            return ResponseEntity.ok(farePaymentService.getQrTokenStatus(passenger, body.get("payload")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/nfc/token")
    public ResponseEntity<?> generateMobileNfcToken(@AuthenticationPrincipal Passenger passenger) {
        if (passenger == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized - please login again"));
        }

        try {
            return ResponseEntity.ok(farePaymentService.generateMobileNfcToken(passenger));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/nfc")
    public ResponseEntity<?> payByNfc(
            @AuthenticationPrincipal Passenger passenger,
            @RequestBody(required = false) Map<String, String> body) {
        if (passenger == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized - please login again"));
        }

        try {
            String plateNumber = body != null ? body.get("plateNumber") : null;
            return ResponseEntity.ok(farePaymentService.processPassengerNfcPayment(passenger, plateNumber));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
