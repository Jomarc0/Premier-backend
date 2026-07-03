package com.premier.controller;

import com.premier.model.Passenger;
import com.premier.response.ApiResponse;
import com.premier.service.BalanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/passenger")
@RequiredArgsConstructor
public class BalanceController {

    private final BalanceService balanceService;

    @GetMapping("/balance")
    public ResponseEntity<?> getBalance(
            @AuthenticationPrincipal Passenger passenger) {

        if (passenger == null) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error(
                    "Unauthorized - please login again"));
        }

        return ResponseEntity.ok(
            balanceService.getBalance(passenger));
    }
}
