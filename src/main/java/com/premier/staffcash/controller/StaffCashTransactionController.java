package com.premier.staffcash.controller;

import com.premier.admin.model.Admin;
import com.premier.staffcash.service.StaffCashFareService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/staff/cash-transactions")
@RequiredArgsConstructor
public class StaffCashTransactionController {
    private final StaffCashFareService service;

    @GetMapping("/today")
    public ResponseEntity<?> today(Authentication authentication) {
        return ResponseEntity.ok(service.today((Admin) authentication.getPrincipal()));
    }
}
