package com.premier.service;

import com.premier.model.Passenger;
import com.premier.model.Transaction;
import com.premier.model.TransactionType;
import com.premier.repository.TransactionRepository;
import com.premier.response.ApiResponse;
import com.premier.response.TransactionResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;

    public ApiResponse<Page<TransactionResponse>> getTransactions(
            Passenger passenger, int page, int size) {

        Page<Transaction> transactions = transactionRepository
                .findByPassengerIdOrderByCreatedAtDesc(
                        passenger.getId(), PageRequest.of(page, size));

        return ApiResponse.success("Transactions fetched.",
                transactions.map(this::toResponse));
    }

    public ApiResponse<Page<TransactionResponse>> getTransactionsByType(
            Passenger passenger, TransactionType type, int page, int size) {

        Page<Transaction> transactions = transactionRepository
                .findByPassengerIdAndTypeOrderByCreatedAtDesc(
                        passenger.getId(), type, PageRequest.of(page, size));

        return ApiResponse.success("Transactions fetched.", transactions.map(this::toResponse));
    }

    private TransactionResponse toResponse(Transaction t) {
        return TransactionResponse.builder()
                .id(t.getId())
                .type(t.getType())
                .status(t.getStatus())
                .amount(t.getAmount())
                .balanceBefore(t.getBalanceBefore())
                .balanceAfter(t.getBalanceAfter())
                .referenceNumber(t.getReferenceNumber())
                .description(t.getDescription())
                .createdAt(t.getCreatedAt())
                .build();
    }
}