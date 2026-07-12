package com.premier.staffcash.response;

import com.premier.staffcash.model.StaffRemittanceStatus;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Builder
public record AdminStaffCashSummary(Long staffId, String staffName, LocalDate date,
        long regularCount, long discountedCount, long totalTransactions,
        BigDecimal expectedCash, BigDecimal actualCashReceived, BigDecimal difference,
        String remittanceState, StaffRemittanceStatus result, LocalDateTime confirmedAt) {}
