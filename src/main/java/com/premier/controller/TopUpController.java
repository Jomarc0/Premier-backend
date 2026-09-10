package com.premier.controller;

import com.premier.model.Passenger;
import com.premier.request.TopUpRequestDto;
import com.premier.response.ApiResponse;
import com.premier.service.PayMongoService;
import com.premier.service.TopUpExpirationService;
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
    private final TopUpExpirationService expirationService;

    @GetMapping("/pending")
    public ResponseEntity<?> pending(@AuthenticationPrincipal Passenger passenger,
                                     @RequestParam(defaultValue = "0") int page) {
        if (passenger == null) return unauthorizedPassenger();
        return ResponseEntity.ok(payMongoService.pendingTopUps(passenger, page));
    }

    //Initiate top-up — creates PayMongo link
    @PostMapping("/initiate")
    public ResponseEntity<?> initiateTopUp(
            @AuthenticationPrincipal Passenger passenger,
            @Valid @RequestBody TopUpRequestDto request) {
        if (passenger == null) {
            return unauthorizedPassenger();
        }

        // First, check if the passenger has any pending top-ups that have already expired
        // If so, expire them to allow a new top-up
        expirationService.expireIfPendingAndExpired(passenger.getId());

        var result = payMongoService.initiateTopUp(passenger, request);
        return ResponseEntity.status(result.isSuccess() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(result);
    }

    // Called after passenger completes payment
    @PostMapping("/verify/{referenceNumber}")
    public ResponseEntity<?> verifyPayment(
            @AuthenticationPrincipal Passenger passenger,
            @PathVariable String referenceNumber) {
        if (passenger == null) {
            return unauthorizedPassenger();
        }

        return ResponseEntity.ok(
                payMongoService.processPayment(passenger, referenceNumber));
    }

    
    @GetMapping("/status/{referenceNumber}")
    public ResponseEntity<?> checkStatus(
            @AuthenticationPrincipal Passenger passenger,
            @PathVariable String referenceNumber) {
        if (passenger == null) {
            return unauthorizedPassenger();
        }

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

    private ResponseEntity<?> unauthorizedPassenger() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Unauthorized - please login again"));
    }
    
}
