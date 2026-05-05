package com.premier.controller;

import com.premier.model.Passenger;
import com.premier.model.TransactionType;
import com.premier.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation
        .AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/passenger/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @GetMapping
    public ResponseEntity<?> getTransactions(
            @AuthenticationPrincipal Passenger passenger,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(
            transactionService.getTransactions(
                passenger, page, size));
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<?> getByType(
            @AuthenticationPrincipal Passenger passenger,
            @PathVariable TransactionType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(
            transactionService.getTransactionsByType(
                passenger, type, page, size));
    }
}