package com.premier.service;

import com.premier.model.Passenger;
import com.premier.response.ApiResponse;
import com.premier.response.BalanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BalanceService {

    public ApiResponse<BalanceResponse> getBalance(Passenger passenger) {
        return ApiResponse.success(
            "Balance fetched.",
            BalanceResponse.builder()
                .id(passenger.getId())
                .cardNumber(mask(passenger.getCardNumber()))
                .balance(passenger.getBalance())
                .build()
        );
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        int visible = Math.min(4, trimmed.length());
        return "****" + trimmed.substring(trimmed.length() - visible);
    }
}
