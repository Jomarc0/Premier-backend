package com.premier.staffcash.response;

import com.premier.staffcash.model.StaffCashCardPurpose;
import com.premier.staffcash.model.StaffCashCardStatus;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record StaffCashCardResponse(Long id, Long staffId, String staffName, String maskedRfidUid,
                                    StaffCashCardPurpose purpose, StaffCashCardStatus status,
                                    LocalDateTime registeredAt) {}
