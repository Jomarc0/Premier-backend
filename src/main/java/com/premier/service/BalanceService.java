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
                .cardNumber(passenger.getCardNumber())
                .balance(passenger.getBalance())
                .build()
        );
    }
}