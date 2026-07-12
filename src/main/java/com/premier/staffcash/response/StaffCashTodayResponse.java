package com.premier.staffcash.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Builder
public record StaffCashTodayResponse(LocalDate date, long regularCount, long discountedCount,
                                     long totalPassengers, BigDecimal expectedCash,
                                     List<StaffCashTransactionItem> transactions) {}
