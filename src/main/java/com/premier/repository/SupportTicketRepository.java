package com.premier.repository;

import org.springframework.data.jpa.repository.JpaRepository;  
import com.premier.model.SupportTicket;
import com.premier.model.TicketStatus;
import java.util.List;
import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    List<SupportTicket> findByPassengerIdOrderByCreatedAtDesc(Long passengerId);

    List<SupportTicket> findByPassengerIdAndStatusOrderByCreatedAtDesc(
            Long passengerId, TicketStatus status);

    Optional<SupportTicket> findByIdAndPassengerId(Long id, Long passengerId);
}