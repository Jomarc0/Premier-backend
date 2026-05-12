package com.premier.service;

import com.premier.exception.PassengerNotFoundException;
import com.premier.model.*;
import com.premier.repository.SupportTicketRepository;
import com.premier.request.SupportTicketRequest;
import com.premier.request.TicketReplyRequest;
import com.premier.response.ApiResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupportService {

    private final SupportTicketRepository supportTicketRepository;
    private final FirebaseService firebaseService;

    @Transactional
    public ApiResponse<SupportTicket> createTicket(Passenger passenger, SupportTicketRequest request) {
        TicketMessage firstMessage = TicketMessage.builder()
                .senderType("PASSENGER")
                .message(request.getMessage())
                .build();

        SupportTicket ticket = SupportTicket.builder()
                .passenger(passenger)
                .subject(request.getSubject())
                .category(request.getCategory())
                .status(TicketStatus.OPEN)
                .build();

        firstMessage.setTicket(ticket);
        ticket.getMessages().add(firstMessage);

        supportTicketRepository.save(ticket);

        // Auto-reply bot
        String botReply = generateBotReply(request.getCategory(), request.getMessage());
        if (botReply != null) {
            TicketMessage botMessage = TicketMessage.builder()
                    .ticket(ticket)
                    .senderType("BOT")
                    .message(botReply)
                    .build();
            ticket.getMessages().add(botMessage);
            supportTicketRepository.save(ticket);
        }

        return ApiResponse.success("Support ticket created.", ticket);
    }

    public ApiResponse<List<SupportTicket>> getMyTickets(Passenger passenger) {
        List<SupportTicket> tickets = supportTicketRepository
                .findByPassengerIdOrderByCreatedAtDesc(passenger.getId());
        return ApiResponse.success("Tickets fetched.", tickets);
    }

    public ApiResponse<SupportTicket> getTicketById(Passenger passenger, Long ticketId) {
        SupportTicket ticket = supportTicketRepository
                .findByIdAndPassengerId(ticketId, passenger.getId())
                .orElseThrow(() -> new PassengerNotFoundException("Ticket not found."));
        return ApiResponse.success("Ticket fetched.", ticket);
    }

    @Transactional
    public ApiResponse<SupportTicket> replyToTicket(
            Passenger passenger, Long ticketId, TicketReplyRequest request) {

        SupportTicket ticket = supportTicketRepository
                .findByIdAndPassengerId(ticketId, passenger.getId())
                .orElseThrow(() -> new PassengerNotFoundException("Ticket not found."));

        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new RuntimeException("Cannot reply to a closed ticket.");
        }

        TicketMessage message = TicketMessage.builder()
                .ticket(ticket)
                .senderType("PASSENGER")
                .message(request.getMessage())
                .build();

        ticket.getMessages().add(message);
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        supportTicketRepository.save(ticket);

        return ApiResponse.success("Reply sent.", ticket);
    }

    private String generateBotReply(String category, String message) {
        return switch (category.toUpperCase()) {
            case "BALANCE" -> "Thank you for reaching out! For balance inquiries, " +
                    "you can check your current balance anytime in the app. " +
                    "If your balance is incorrect, our team will investigate within 24 hours.";
            case "TOPUP" -> "For top-up issues, please ensure your payment was completed " +
                    "successfully. PayMongo top-ups reflect within 5-10 minutes. " +
                    "If not received after 30 minutes, please attach your payment receipt.";
            case "RFID" -> "For lost or damaged RFID cards, please visit the nearest " +
                    "Premier Transit office with a valid ID. Card replacement fee is ₱150.";
            case "FARE" -> "Fare deductions are automatically processed upon tap-in/tap-out. " +
                    "If you believe a fare was incorrectly charged, our team will review it.";
            default -> "Thank you for contacting Premier Transit Support! " +
                    "A support agent will respond within 24 hours.";
        };
    }
}