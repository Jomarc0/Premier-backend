package com.premier.support.repository;

import com.premier.support.model.SupportTicket;
import com.premier.support.model.SupportTicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {
    List<SupportTicket> findAllByOrderByCreatedAtDesc();
    long countByStatus(SupportTicketStatus status);
    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
