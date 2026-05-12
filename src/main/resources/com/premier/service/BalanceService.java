package com.premier.service;

import com.premier.model.Passenger;
import com.premier.response.ApiResponse;
import com.premier.response.BalanceResponse;

import org.springframework.stereotype.Service;

@Service
public class BalanceService {

    public ApiResponse<BalanceResponse> getBalance(Passenger passenger) {
        return ApiResponse.success("Balance fetched.",
                BalanceResponse.builder()
                        .id(passenger.getId())
                        .userId(passenger.getUserId())
                        .cardNumber(passenger.getCardNumber())
                        .balance(passenger.getBalance())
                        .build());
    }
}