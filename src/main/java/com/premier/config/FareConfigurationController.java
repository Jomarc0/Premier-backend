package com.premier.config;

import com.premier.admin.model.Admin;
import com.premier.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;

@RestController @RequiredArgsConstructor
public class FareConfigurationController {
    private final FareConfigurationService service;
    @GetMapping("/api/admin/fares")
    public ResponseEntity<?> adminGet() { return ResponseEntity.ok(ApiResponse.success("Active fare configuration fetched.", service.getActiveDto())); }
    @PutMapping("/api/admin/fares")
    public ResponseEntity<?> update(@Valid @RequestBody UpdateRequest request, @AuthenticationPrincipal Admin admin) {
        return ResponseEntity.ok(ApiResponse.success("Fare configuration updated.",service.toDto(service.update(request.regularFare(),request.discountedFare(),request.version(),admin))));
    }
    @GetMapping("/api/passenger/fare/config")
    public ResponseEntity<?> passengerGet() { return ResponseEntity.ok(ApiResponse.success("Active fare configuration fetched.", service.getActiveDto())); }
    @GetMapping("/api/rfid/fare-config")
    public ResponseEntity<?> deviceGet() { return ResponseEntity.ok(ApiResponse.success("Active fare configuration fetched.", service.getActiveDto())); }
    public record UpdateRequest(@NotNull @DecimalMin("0.00") @Digits(integer=8,fraction=2) BigDecimal regularFare,
                                @NotNull @DecimalMin("0.00") @Digits(integer=8,fraction=2) BigDecimal discountedFare,
                                @NotNull Long version) {}
}
