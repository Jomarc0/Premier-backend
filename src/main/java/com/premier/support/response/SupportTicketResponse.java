package com.premier.support.response;

import com.premier.support.model.SupportTicket;
import com.premier.support.model.SupportTicketIssueType;
import com.premier.support.model.SupportTicketPriority;
import com.premier.support.model.SupportTicketStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SupportTicketResponse {
    private Long id;
    private String ticketNumber;
    private String cardNumber;
    private String maskedCardNumber;
    private Long passengerId;
    private String passengerName;
    private String currentRfidUid;
    private String email;
    private SupportTicketIssueType issueType;
    private String reason;
    private SupportTicketStatus status;
    private SupportTicketPriority priority;
    private String adminNotes;
    private Long handledById;
    private String handledByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime resolvedAt;

    public static SupportTicketResponse from(SupportTicket ticket) {
        return SupportTicketResponse.builder()
                .id(ticket.getId())
                .ticketNumber(ticket.getTicketNumber())
                .cardNumber(ticket.getCardNumber())
                .maskedCardNumber(mask(ticket.getCardNumber()))
                .passengerId(ticket.getPassenger() != null ? ticket.getPassenger().getId() : null)
                .passengerName(ticket.getPassenger() != null ? "Passenger #" + ticket.getPassenger().getId() : null)
                .currentRfidUid(ticket.getPassenger() != null ? ticket.getPassenger().getRfidUid() : null)
                .email(ticket.getEmail())
                .issueType(ticket.getIssueType())
                .reason(ticket.getReason())
                .status(ticket.getStatus())
                .priority(ticket.getPriority())
                .adminNotes(ticket.getAdminNotes())
                .handledById(ticket.getHandledBy() != null ? ticket.getHandledBy().getId() : null)
                .handledByName(ticket.getHandledBy() != null ? ticket.getHandledBy().getFullName() : null)
                .createdAt(ticket.getCreatedAt())
                .updatedAt(ticket.getUpdatedAt())
                .resolvedAt(ticket.getResolvedAt())
                .build();
    }

    private static String mask(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 8) return "****";
        return cardNumber.substring(0, 4) + "****" + cardNumber.substring(cardNumber.length() - 4);
    }
}
