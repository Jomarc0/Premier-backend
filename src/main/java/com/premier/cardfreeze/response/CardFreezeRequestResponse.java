package com.premier.cardfreeze.response;

import com.premier.cardfreeze.model.CardFreezeRequest;
import com.premier.cardfreeze.model.CardFreezeRequestStatus;
import com.premier.cardfreeze.model.CardFreezeRequestType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CardFreezeRequestResponse {
    private Long id;
    private Long passengerId;
    private String passengerName;
    private String maskedCardNumber;
    private CardFreezeRequestType requestType;
    private String reason;
    private CardFreezeRequestStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime reviewedAt;
    private Long reviewedByAdminId;
    private String reviewedByAdminName;
    private String adminRemarks;

    public static CardFreezeRequestResponse from(CardFreezeRequest request) {
        return CardFreezeRequestResponse.builder()
                .id(request.getId())
                .passengerId(request.getPassenger().getId())
                .passengerName(passengerName(request))
                .maskedCardNumber(mask(request.getRfidCard().getCardNumber()))
                .requestType(request.getRequestType())
                .reason(request.getReason())
                .status(request.getStatus())
                .createdAt(request.getCreatedAt())
                .reviewedAt(request.getReviewedAt())
                .reviewedByAdminId(request.getReviewedByAdmin() != null ? request.getReviewedByAdmin().getId() : null)
                .reviewedByAdminName(request.getReviewedByAdmin() != null ? request.getReviewedByAdmin().getFullName() : null)
                .adminRemarks(request.getAdminRemarks())
                .build();
    }

    private static String passengerName(CardFreezeRequest request) {
        return "Passenger #" + request.getPassenger().getId();
    }

    private static String mask(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 8) return "****";
        return cardNumber.substring(0, 4) + "****" + cardNumber.substring(cardNumber.length() - 4);
    }
}
