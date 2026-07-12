package com.premier.support.service;

import com.premier.admin.model.ActivityLog;
import com.premier.admin.model.Admin;
import com.premier.admin.repository.ActivityLogRepository;
import com.premier.model.Passenger;
import com.premier.model.PassengerStatus;
import com.premier.repository.PassengerRepository;
import com.premier.response.ApiResponse;
import com.premier.service.FirebaseService;
import com.premier.support.model.SupportTicket;
import com.premier.support.model.SupportTicketIssueType;
import com.premier.support.model.SupportTicketPriority;
import com.premier.support.model.SupportTicketStatus;
import com.premier.support.repository.SupportTicketRepository;
import com.premier.support.response.SupportTicketResponse;
import com.premier.support.response.SupportTicketSummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupportTicketService {

    private final SupportTicketRepository ticketRepository;
    private final PassengerRepository passengerRepository;
    private final ActivityLogRepository activityLogRepository;
    private final FirebaseService firebaseService;
    private final SupportEmailService supportEmailService;

    @Transactional
    public ApiResponse<SupportTicketResponse> createPublicTicket(String cardNumber,
                                                                 String email,
                                                                 SupportTicketIssueType issueType,
                                                                 String reason) {
        String normalizedCard = cleanCardNumber(cardNumber);
        String normalizedEmail = clean(email);
        String cleanedReason = cleanReason(reason);

        Passenger passenger = passengerRepository.findByCardNumber(normalizedCard)
                .orElseThrow(() -> new RuntimeException("Card number not found. Please check your card number or contact support."));

        SupportTicket ticket;
        try {
            ticket = SupportTicket.builder()
                    .ticketNumber(nextTicketNumber())
                    .cardNumber(normalizedCard)
                    .passenger(passenger)
                    .email(normalizedEmail)
                    .issueType(issueType)
                    .reason(cleanedReason)
                    .status(SupportTicketStatus.PENDING)
                    .priority(priorityFor(issueType, cleanedReason))
                    .build();
            ticketRepository.save(ticket);
        } catch (DataAccessException ex) {
            log.error("Support ticket database write failed for masked card {}", mask(normalizedCard), ex);
            throw new RuntimeException("Support ticket storage is not ready. Please ask admin to run the support ticket database migration.");
        }

        return ApiResponse.success(
                "Your ticket has been submitted successfully. Your ticket number is " + ticket.getTicketNumber() + ". Please wait for admin confirmation through your email.",
                SupportTicketResponse.from(ticket));
    }

    public ApiResponse<List<SupportTicketResponse>> listTickets() {
        return ApiResponse.success("Support tickets fetched.",
                ticketRepository.findAllByOrderByCreatedAtDesc()
                        .stream()
                        .map(SupportTicketResponse::from)
                        .toList());
    }

    public ApiResponse<SupportTicketResponse> getTicket(Long id) {
        return ApiResponse.success("Support ticket fetched.", SupportTicketResponse.from(findTicket(id)));
    }

    public ApiResponse<SupportTicketSummaryResponse> summary() {
        return ApiResponse.success("Support ticket summary fetched.",
                SupportTicketSummaryResponse.builder()
                        .pending(ticketRepository.countByStatus(SupportTicketStatus.PENDING))
                        .inReview(ticketRepository.countByStatus(SupportTicketStatus.IN_REVIEW))
                        .build());
    }

    @Transactional
    public ApiResponse<SupportTicketResponse> updateStatus(Admin admin, Long id, SupportTicketStatus status) {
        if (status == SupportTicketStatus.RESOLVED || status == SupportTicketStatus.REJECTED) {
            throw new RuntimeException("Use the resolve or reject action to close a ticket.");
        }
        SupportTicket ticket = findTicket(id);
        ticket.setStatus(status);
        ticket.setHandledBy(admin);
        ticketRepository.save(ticket);
        logAdminAction(admin, ticket, "UPDATE_SUPPORT_TICKET_STATUS", "Changed status to " + status);
        return ApiResponse.success("Support ticket status updated.", SupportTicketResponse.from(ticket));
    }

    @Transactional
    public ApiResponse<SupportTicketResponse> updateNotes(Admin admin, Long id, String notes) {
        SupportTicket ticket = findTicket(id);
        ticket.setAdminNotes(cleanReason(notes));
        ticket.setHandledBy(admin);
        ticketRepository.save(ticket);
        logAdminAction(admin, ticket, "UPDATE_SUPPORT_TICKET_NOTES", "Updated admin notes.");
        return ApiResponse.success("Admin notes updated.", SupportTicketResponse.from(ticket));
    }

    @Transactional
    public ApiResponse<SupportTicketResponse> freezeCard(Admin admin, Long id, String notes) {
        SupportTicket ticket = findTicket(id);
        Passenger passenger = passengerRepository.findLockedById(ticket.getPassenger().getId())
                .orElseThrow(() -> new RuntimeException("Passenger not found."));
        passenger.setStatus(PassengerStatus.FROZEN);
        passengerRepository.save(passenger);

        ticket.setStatus(SupportTicketStatus.IN_REVIEW);
        ticket.setHandledBy(admin);
        appendNotes(ticket, notes != null && !notes.isBlank() ? notes : "Card frozen by admin.");
        ticketRepository.save(ticket);

        logAdminAction(admin, ticket, "FREEZE_CARD_FROM_SUPPORT_TICKET",
                "Froze masked card " + mask(passenger.getCardNumber()) + " from support ticket " + ticket.getTicketNumber());
        firebaseService.sendNotification(passenger.getFcmToken(),
                "RFID card frozen",
                "Your support ticket is under review and your RFID card has been frozen.");
        return ApiResponse.success("Card frozen and ticket moved to in review.", SupportTicketResponse.from(ticket));
    }

    @Transactional
    public ApiResponse<SupportTicketResponse> replaceRfidUid(Admin admin, Long id, String newRfidUid, String notes) {
        SupportTicket ticket = findTicket(id);
        Passenger passenger = passengerRepository.findLockedById(ticket.getPassenger().getId())
                .orElseThrow(() -> new RuntimeException("Passenger not found."));
        String normalizedUid = normalizeRfidUid(newRfidUid);

        passengerRepository.findByRfidUid(normalizedUid).ifPresent(existing -> {
            if (!existing.getId().equals(passenger.getId())) {
                throw new RuntimeException("New RFID UID is already assigned to another card.");
            }
        });

        String oldUid = passenger.getRfidUid();
        passenger.setRfidUid(normalizedUid);
        passenger.setStatus(PassengerStatus.ACTIVE);
        passengerRepository.save(passenger);

        ticket.setStatus(SupportTicketStatus.IN_REVIEW);
        ticket.setHandledBy(admin);
        appendNotes(ticket, notes != null && !notes.isBlank()
                ? notes
                : "Replacement RFID UID saved. Old UID: " + safeUid(oldUid) + ", new UID: " + safeUid(normalizedUid) + ".");
        ticketRepository.save(ticket);

        logAdminAction(admin, ticket, "REPLACE_RFID_UID_FROM_SUPPORT_TICKET",
                "Changed RFID UID for masked card " + mask(passenger.getCardNumber()) + " from " + safeUid(oldUid) + " to " + safeUid(normalizedUid));
        return ApiResponse.success("Replacement RFID UID saved.", SupportTicketResponse.from(ticket));
    }

    @Transactional
    public ApiResponse<SupportTicketResponse> resolve(Admin admin, Long id, String notes) {
        SupportTicket ticket = findTicket(id);
        ticket.setStatus(SupportTicketStatus.RESOLVED);
        ticket.setHandledBy(admin);
        ticket.setResolvedAt(LocalDateTime.now());
        appendNotes(ticket, notes);
        ticketRepository.save(ticket);
        logAdminAction(admin, ticket, "RESOLVE_SUPPORT_TICKET", "Resolved support ticket " + ticket.getTicketNumber());
        sendTicketDecisionSafely(ticket,
                "Premier Transport support ticket resolved",
                "Your support ticket has been resolved by the Premier Transport support team. "
                        + publicAdminMessage(ticket.getAdminNotes()));
        return ApiResponse.success("Support ticket resolved.", SupportTicketResponse.from(ticket));
    }

    @Transactional
    public ApiResponse<SupportTicketResponse> reject(Admin admin, Long id, String notes) {
        if (notes == null || notes.trim().isBlank()) {
            throw new RuntimeException("Admin notes are required when rejecting a ticket.");
        }
        SupportTicket ticket = findTicket(id);
        ticket.setStatus(SupportTicketStatus.REJECTED);
        ticket.setHandledBy(admin);
        ticket.setResolvedAt(LocalDateTime.now());
        appendNotes(ticket, notes);
        ticketRepository.save(ticket);
        logAdminAction(admin, ticket, "REJECT_SUPPORT_TICKET", "Rejected support ticket " + ticket.getTicketNumber());
        sendTicketDecisionSafely(ticket,
                "Premier Transport support ticket update",
                "Your support ticket was reviewed by the Premier Transport support team but was not approved. "
                        + publicAdminMessage(ticket.getAdminNotes()));
        return ApiResponse.success("Support ticket rejected.", SupportTicketResponse.from(ticket));
    }

    private void sendTicketDecisionSafely(SupportTicket ticket, String subject, String message) {
        try {
            supportEmailService.sendTicketDecision(ticket, subject, message);
        } catch (RuntimeException ex) {
            log.warn("Ticket {} decision saved, but the notification email could not be sent: {}",
                    ticket.getTicketNumber(), ex.getMessage());
        }
    }

    private SupportTicket findTicket(Long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Support ticket not found."));
    }

    private String nextTicketNumber() {
        LocalDateTime start = Year.now().atDay(1).atStartOfDay();
        LocalDateTime end = start.plusYears(1);
        long sequence = ticketRepository.countByCreatedAtBetween(start, end) + 1;
        return "TICKET-" + Year.now().getValue() + "-" + String.format("%06d", sequence);
    }

    private SupportTicketPriority priorityFor(SupportTicketIssueType issueType, String reason) {
        String text = (reason == null ? "" : reason).toLowerCase();
        if (issueType == SupportTicketIssueType.LOST_CARD || text.contains("stolen")) {
            return SupportTicketPriority.HIGH;
        }
        if (issueType == SupportTicketIssueType.FREEZE_CARD) {
            return SupportTicketPriority.HIGH;
        }
        if (issueType == SupportTicketIssueType.RFID_NOT_WORKING || issueType == SupportTicketIssueType.TOP_UP_ISSUE) {
            return SupportTicketPriority.NORMAL;
        }
        return SupportTicketPriority.NORMAL;
    }

    private void appendNotes(SupportTicket ticket, String notes) {
        String cleaned = cleanReason(notes);
        if (cleaned == null || cleaned.isBlank()) return;
        ticket.setAdminNotes(ticket.getAdminNotes() == null || ticket.getAdminNotes().isBlank()
                ? cleaned
                : ticket.getAdminNotes() + "\n" + cleaned);
    }

    private void logAdminAction(Admin admin, SupportTicket ticket, String action, String details) {
        activityLogRepository.save(ActivityLog.builder()
                .admin(admin)
                .action(action)
                .targetType("SUPPORT_TICKET")
                .targetId(ticket.getId())
                .userId(ticket.getPassenger() != null ? ticket.getPassenger().getId() : null)
                .details(details)
                .status("SUCCESS")
                .build());
    }

    private String cleanCardNumber(String value) {
        String cleaned = clean(value);
        if (cleaned == null || cleaned.isBlank()) {
            throw new RuntimeException("Card Number is required.");
        }
        return cleaned.replaceAll("\\s+", "");
    }

    private String clean(String value) {
        return value == null ? null : value.trim();
    }

    private String cleanReason(String reason) {
        if (reason == null) return null;
        return reason.replaceAll("<[^>]*>", "")
                .replaceAll("[<>\"'&;]", "")
                .trim();
    }

    private String normalizeRfidUid(String rfidUid) {
        if (rfidUid == null || rfidUid.isBlank()) {
            throw new RuntimeException("New RFID UID is required.");
        }
        return rfidUid.trim().replaceAll("[^A-Fa-f0-9]", "").toUpperCase();
    }

    private String safeUid(String value) {
        if (value == null || value.isBlank()) return "none";
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }

    private String mask(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 8) return "****";
        return cardNumber.substring(0, 4) + "****" + cardNumber.substring(cardNumber.length() - 4);
    }

    private String publicAdminMessage(String notes) {
        if (notes == null || notes.isBlank()) {
            return "Please visit the nearest Premier Transport office if you need more information.";
        }
        return notes;
    }
}
