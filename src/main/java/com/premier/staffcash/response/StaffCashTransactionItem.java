package com.premier.staffcash.response;

import com.premier.staffcash.model.StaffCashCardPurpose;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record StaffCashTransactionItem(Long id, String referenceNumber, String plateNumber,
                                       String deviceId, String route, String terminal,
                                       StaffCashCardPurpose fareCategory, BigDecimal finalFare,
                                       LocalDateTime createdAt) {}
