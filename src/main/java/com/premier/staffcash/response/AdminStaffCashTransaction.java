package com.premier.staffcash.response;

import com.premier.staffcash.model.StaffCashCardPurpose;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record AdminStaffCashTransaction(Long id, Long staffId, String staffName,
        String referenceNumber, String plateNumber, String deviceId, Long driverShiftId,
        String route, String terminal, StaffCashCardPurpose fareCategory,
        BigDecimal baseFare, BigDecimal discountAmount, BigDecimal finalFare,
        LocalDateTime createdAt) {}
