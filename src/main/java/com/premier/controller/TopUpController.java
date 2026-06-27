package com.premier.controller;

import com.premier.request.TopUpRequestDto;
import com.premier.model.Passenger;
import com.premier.service.PayMongoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/passenger/topup")
@RequiredArgsConstructor
public class TopUpController {

    private final PayMongoService payMongoService;

    //Initiate top-up — creates PayMongo link
    @PostMapping("/initiate")
    public ResponseEntity<?> initiateTopUp(
            @AuthenticationPrincipal Passenger passenger,
            @Valid @RequestBody TopUpRequestDto request) {
        return ResponseEntity.ok(
                payMongoService.initiateTopUp(passenger, request));
    }

    // Called after passenger completes payment
    @PostMapping("/verify/{referenceNumber}")
    public ResponseEntity<?> verifyPayment(
            @AuthenticationPrincipal Passenger passenger,
            @PathVariable String referenceNumber) {
        return ResponseEntity.ok(
                payMongoService.processPayment(passenger, referenceNumber));
    }

    
    @GetMapping("/status/{referenceNumber}")
    public ResponseEntity<?> checkStatus(
            @AuthenticationPrincipal Passenger passenger,
            @PathVariable String referenceNumber) {
        return ResponseEntity.ok(
                payMongoService.checkPaymentStatus(passenger, referenceNumber));
    }

    
    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "Paymongo-Signature", required = false) String signature) {

        try {
            payMongoService.handleWebhook(rawBody, signature);
            return ResponseEntity.ok().build();
        } catch (SecurityException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
    
}
