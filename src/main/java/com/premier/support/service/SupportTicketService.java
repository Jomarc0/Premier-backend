package com.premier.support.service;

import com.premier.admin.model.ActivityLog;
import com.premier.admin.model.Admin;
import com.premier.admin.repository.ActivityLogRepository;
import com.premier.model.Passenger;
import com.premier.model.PassengerStatus;
import com.premier.repository.PassengerRepository;
import com.premier.response.ApiResponse;
import com.premier.service.FirebaseService;
import com.premier.realtime.RealtimeEventPublisher;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupportTicketService {

    private final SupportTicketRepository ticketRepository;
    private final PassengerRepository passengerRepository;
    private final ActivityLogRepository activityLogRepository;
    private final FirebaseService firebaseService;
    private final SupportEmailService supportEmailService;
    private final RealtimeEventPublisher realtimeEventPublisher;

    @Transactional
    public ApiResponse<SupportTicketResponse> createPassengerTicket(Passenger passenger,
                                                                 String email,
                                                                 SupportTicketIssueType issueType,
                                                                 String reason) {
        if (passenger == null || passenger.getId() == null || passenger.getCardNumber() == null) {
            throw new RuntimeException("Please log in before creating a support ticket.");
        }
        String normalizedCard = cleanCardNumber(passenger.getCardNumber());
        String normalizedEmail = clean(email);
        String cleanedReason = cleanReason(reason);

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

        realtimeEventPublisher.adminAndPassenger(passenger.getId(), "TICKET_CREATED", "SUPPORT_TICKET", ticket.getId());

        return ApiResponse.success(
                "Your ticket has been submitted successfully. Your ticket number is " + ticket.getTicketNumber() + ". Please wait for admin confirmation through your email.",
                SupportTicketResponse.from(ticket));
    }

    /** Requires a full passenger session created after TOTP verification. */
    @Transactional
    public ApiResponse<SupportTicketResponse> reportLostCard(Passenger authenticatedPassenger, String email) {
        requirePassenger(authenticatedPassenger);
        Passenger passenger = passengerRepository.findLockedById(authenticatedPassenger.getId())
                .orElseThrow(() -> new RuntimeException("Passenger not found."));
        String normalizedEmail = clean(email);
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            throw new RuntimeException("Email address is required so we can send your support update.");
        }

        SupportTicket existing = ticketRepository
                .findByPassengerIdAndIssueTypeOrderByCreatedAtDesc(passenger.getId(), SupportTicketIssueType.LOST_CARD)
                .stream()
                .filter(ticket -> ticket.getStatus() == SupportTicketStatus.PENDING
                        || ticket.getStatus() == SupportTicketStatus.IN_REVIEW)
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return ApiResponse.success("Your lost-card report is already being handled. Your card remains frozen.",
                    SupportTicketResponse.from(existing));
        }

        passenger.setStatus(PassengerStatus.FROZEN);
        passengerRepository.save(passenger);
        SupportTicket ticket = SupportTicket.builder()
                .ticketNumber(nextTicketNumber())
                .cardNumber(cleanCardNumber(passenger.getCardNumber()))
                .passenger(passenger)
                .email(normalizedEmail)
                .issueType(SupportTicketIssueType.LOST_CARD)
                .reason("Passenger reported this card lost after completing two-factor authentication. Card automatically frozen.")
                .status(SupportTicketStatus.PENDING)
                .priority(SupportTicketPriority.HIGH)
                .build();
        ticketRepository.save(ticket);
        realtimeEventPublisher.adminAndPassenger(passenger.getId(), "TICKET_CREATED", "SUPPORT_TICKET", ticket.getId());
        realtimeEventPublisher.adminAndPassenger(passenger.getId(), "PASSENGER_UPDATED", "PASSENGER", passenger.getId());
        firebaseService.sendNotification(passenger, "Card frozen", "Your lost-card report was received and your RFID card has been frozen.",
                Map.of("type", "TICKET"));
        log.warn("Lost-card report accepted and card frozen for masked card {}", mask(passenger.getCardNumber()));

        return ApiResponse.success("Your card has been frozen and a high-priority lost-card ticket was created: "
                        + ticket.getTicketNumber() + ". Visit the office with a valid ID for replacement.",
                SupportTicketResponse.from(ticket));
    }

    public ApiResponse<List<SupportTicketResponse>> listTickets() {
        return ApiResponse.success("Support tickets fetched.",
                ticketRepository.findAllByOrderByCreatedAtDesc()
                        .stream()
                        .map(SupportTicketResponse::from)
                        .toList());
    }

    public ApiResponse<List<SupportTicketResponse>> listPassengerTickets(Passenger passenger) {
        requirePassenger(passenger);
        return ApiResponse.success("Your support tickets fetched.",
                ticketRepository.findByPassengerIdOrderByCreatedAtDesc(passenger.getId())
                        .stream()
                        .map(SupportTicketResponse::from)
                        .toList());
    }

    public ApiResponse<SupportTicketResponse> getPassengerTicket(Passenger passenger, Long id) {
        requirePassenger(passenger);
        SupportTicket ticket = ticketRepository.findById(id)
                .filter(candidate -> candidate.getPassenger() != null
                        && passenger.getId().equals(candidate.getPassenger().getId()))
                // Do not reveal whether a ticket owned by another passenger exists.
                .orElseThrow(() -> new RuntimeException("Support ticket not found."));
        return ApiResponse.success("Your support ticket fetched.", SupportTicketResponse.from(ticket));
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
        requireOpenTicket(ticket);
        ticket.setStatus(status);
        ticket.setHandledBy(admin);
        ticketRepository.save(ticket);
        publishTicketUpdate(ticket, "TICKET_STATUS_CHANGED");
        logAdminAction(admin, ticket, "UPDATE_SUPPORT_TICKET_STATUS", "Changed status to " + status);
        return ApiResponse.success("Support ticket status updated.", SupportTicketResponse.from(ticket));
    }

    @Transactional
    public ApiResponse<SupportTicketResponse> updateNotes(Admin admin, Long id, String notes) {
        SupportTicket ticket = findTicket(id);
        ticket.setAdminNotes(cleanReason(notes));
        ticket.setHandledBy(admin);
        ticketRepository.save(ticket);
        publishTicketUpdate(ticket, "TICKET_UPDATED");
        logAdminAction(admin, ticket, "UPDATE_SUPPORT_TICKET_NOTES", "Updated admin notes.");
        return ApiResponse.success("Admin notes updated.", SupportTicketResponse.from(ticket));
    }

    @Transactional
    public ApiResponse<SupportTicketResponse> freezeCard(Admin admin, Long id, String notes) {
        SupportTicket ticket = findTicket(id);
        requireOpenTicket(ticket);
        Passenger passenger = passengerRepository.findLockedById(ticket.getPassenger().getId())
                .orElseThrow(() -> new RuntimeException("Passenger not found."));
        passenger.setStatus(PassengerStatus.FROZEN);
        passengerRepository.save(passenger);

        ticket.setStatus(SupportTicketStatus.IN_REVIEW);
        ticket.setHandledBy(admin);
        appendNotes(ticket, notes != null && !notes.isBlank() ? notes : "Card frozen by admin.");
        ticketRepository.save(ticket);
        publishTicketUpdate(ticket, "TICKET_STATUS_CHANGED");

        logAdminAction(admin, ticket, "FREEZE_CARD_FROM_SUPPORT_TICKET",
                "Froze masked card " + mask(passenger.getCardNumber()) + " from support ticket " + ticket.getTicketNumber());
        firebaseService.sendNotification(passenger,
                "RFID card frozen",
                "Your support ticket is under review and your RFID card has been frozen.", Map.of("type", "TICKET"));
        return ApiResponse.success("Card frozen and ticket moved to in review.", SupportTicketResponse.from(ticket));
    }

    @Transactional
    public ApiResponse<SupportTicketResponse> replaceRfidUid(Admin admin, Long id, String newRfidUid, String notes) {
        SupportTicket ticket = findTicket(id);
        requireOpenTicket(ticket);
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
        publishTicketUpdate(ticket, "TICKET_UPDATED");

        logAdminAction(admin, ticket, "REPLACE_RFID_UID_FROM_SUPPORT_TICKET",
                "Changed RFID UID for masked card " + mask(passenger.getCardNumber()) + " from " + safeUid(oldUid) + " to " + safeUid(normalizedUid));
        return ApiResponse.success("Replacement RFID UID saved.", SupportTicketResponse.from(ticket));
    }

    @Transactional
    public ApiResponse<SupportTicketResponse> resolve(Admin admin, Long id, String notes) {
        // Lock the row for the close-and-email operation so concurrent resolve
        // requests cannot each send a resolution email.
        SupportTicket ticket = findTicketForUpdate(id);
        requireOpenTicket(ticket);
        ticket.setStatus(SupportTicketStatus.RESOLVED);
        ticket.setHandledBy(admin);
        ticket.setResolvedAt(LocalDateTime.now());
        appendNotes(ticket, notes);
        ticketRepository.save(ticket);
        publishTicketUpdate(ticket, "TICKET_STATUS_CHANGED");
        logAdminAction(admin, ticket, "RESOLVE_SUPPORT_TICKET", "Resolved support ticket " + ticket.getTicketNumber());
        boolean emailSent = sendTicketDecisionSafely(ticket,
                "Premier Transport support ticket resolved",
                "Your support ticket has been resolved by the Premier Transport support team. "
                        + publicAdminMessage(ticket.getAdminNotes()));
        return ApiResponse.success(decisionMessage("resolved", emailSent), SupportTicketResponse.from(ticket));
    }

    @Transactional
    public ApiResponse<SupportTicketResponse> reject(Admin admin, Long id, String notes) {
        if (notes == null || notes.trim().isBlank()) {
            throw new RuntimeException("Admin notes are required when rejecting a ticket.");
        }
        SupportTicket ticket = findTicket(id);
        requireOpenTicket(ticket);
        ticket.setStatus(SupportTicketStatus.REJECTED);
        ticket.setHandledBy(admin);
        ticket.setResolvedAt(LocalDateTime.now());
        appendNotes(ticket, notes);
        ticketRepository.save(ticket);
        publishTicketUpdate(ticket, "TICKET_STATUS_CHANGED");
        logAdminAction(admin, ticket, "REJECT_SUPPORT_TICKET", "Rejected support ticket " + ticket.getTicketNumber());
        boolean emailSent = sendTicketDecisionSafely(ticket,
                "Premier Transport support ticket update",
                "Your support ticket was reviewed by the Premier Transport support team but was not approved. "
                        + publicAdminMessage(ticket.getAdminNotes()));
        return ApiResponse.success(decisionMessage("rejected", emailSent), SupportTicketResponse.from(ticket));
    }

    private boolean sendTicketDecisionSafely(SupportTicket ticket, String subject, String message) {
        try {
            return supportEmailService.sendTicketDecision(ticket, subject, message);
        } catch (RuntimeException ex) {
            log.warn("Ticket {} decision saved, but the notification email could not be sent: {}",
                    ticket.getTicketNumber(), ex.getMessage());
            return false;
        }
    }

    private String decisionMessage(String decision, boolean emailSent) {
        return emailSent
                ? "Support ticket " + decision + " and notification email sent."
                : "Support ticket " + decision + ", but the notification email was not sent. Check the backend mail configuration and logs.";
    }

    private SupportTicket findTicket(Long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Support ticket not found."));
    }

    private void requirePassenger(Passenger passenger) {
        if (passenger == null || passenger.getId() == null) {
            throw new RuntimeException("Please log in before viewing support tickets.");
        }
    }

    private void publishTicketUpdate(SupportTicket ticket, String eventType) {
        realtimeEventPublisher.adminAndPassenger(
                ticket.getPassenger() == null ? null : ticket.getPassenger().getId(),
                eventType, "SUPPORT_TICKET", ticket.getId());
    }

    private SupportTicket findTicketForUpdate(Long id) {
        return ticketRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new RuntimeException("Support ticket not found."));
    }

    private void requireOpenTicket(SupportTicket ticket) {
        if (ticket.getStatus() == SupportTicketStatus.RESOLVED || ticket.getStatus() == SupportTicketStatus.REJECTED) {
            throw new RuntimeException("This support ticket is already closed and cannot be changed.");
        }
    }

    private String nextTicketNumber() {
        // Count-based numbers collide when two passengers submit at once. Keep a
        // readable prefix but generate the identifier independently of row count.
        return "TICKET-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
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
