package com.premier.response;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.premier.model.TransactionStatus;
import com.premier.model.TransactionType;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TransactionResponse {
    private Long id;
    private TransactionType type;
    private TransactionStatus status;
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String referenceNumber;
    private String description;
    private LocalDateTime createdAt;
}