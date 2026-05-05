package com.premier.service;

import com.premier.model.Passenger;
import com.premier.model.SupportTicket;
import com.premier.model.TicketMessage;
import com.premier.model.TicketStatus;
import com.premier.request.SupportTicketRequest;
import com.premier.request.TicketReplyRequest;
import com.premier.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Service
@RequiredArgsConstructor
public class SupportService {

    private final SupportTicketRepository supportTicketRepository;

    public Map<String, Object> createTicket(Passenger passenger,
                                             SupportTicketRequest request) {
        // Build first message from request
        TicketMessage firstMessage = TicketMessage.builder()
                .message(request.getMessage())
                .senderType("PASSENGER")
                .build();

        SupportTicket ticket = SupportTicket.builder()
                .passenger(passenger)
                .subject(request.getSubject())
                .category(request.getCategory())
                .status(TicketStatus.OPEN)
                .build();

        // Link message to ticket
        firstMessage.setTicket(ticket);
        ticket.getMessages().add(firstMessage);

        supportTicketRepository.save(ticket);

        Map<String, Object> response = new HashMap<>();
        response.put("ticketId", ticket.getId());
        response.put("message", "Ticket created successfully");
        return response;
    }

    public List<SupportTicket> getMyTickets(Passenger passenger) {
        return supportTicketRepository
                .findByPassengerIdOrderByCreatedAtDesc(passenger.getId()); 
    }

    public SupportTicket getTicketById(Passenger passenger, Long ticketId) {
        return supportTicketRepository
                .findByIdAndPassengerId(ticketId, passenger.getId()) 
                .orElse(null);
    }

    public Map<String, Object> replyToTicket(Passenger passenger,
                                              Long ticketId,
                                              TicketReplyRequest request) {
        SupportTicket ticket = supportTicketRepository
                .findByIdAndPassengerId(ticketId, passenger.getId()) 
                .orElse(null);

        if (ticket == null) return null;

        TicketMessage reply = TicketMessage.builder()
                .ticket(ticket)
                .message(request.getMessage())
                .senderType("PASSENGER")
                .build();

        ticket.getMessages().add(reply);
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        supportTicketRepository.save(ticket);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Reply added successfully");
        return response;
    }
}