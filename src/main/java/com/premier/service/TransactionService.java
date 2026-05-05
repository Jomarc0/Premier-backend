package com.premier.service;

import com.premier.response.ApiResponse;
import com.premier.model.Passenger;
import com.premier.model.Transaction;
import com.premier.model.TransactionType;
import com.premier.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;

    public ApiResponse<Page<Transaction>> getTransactions(
            Passenger passenger, int page, int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<Transaction> transactions =
            transactionRepository
                .findByPassengerIdOrderByCreatedAtDesc(
                    passenger.getId(), pageable);

        return ApiResponse.success(
            "Transactions fetched.", transactions);
    }

    public ApiResponse<Page<Transaction>> getTransactionsByType(
            Passenger passenger,
            TransactionType type,
            int page, int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<Transaction> transactions =
            transactionRepository
                .findByPassengerIdAndTypeOrderByCreatedAtDesc(
                    passenger.getId(), type, pageable);

        return ApiResponse.success(
            "Transactions fetched.", transactions);
    }
}