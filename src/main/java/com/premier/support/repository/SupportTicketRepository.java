package com.premier.support.repository;

import com.premier.support.model.SupportTicket;
import com.premier.support.model.SupportTicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {
    List<SupportTicket> findAllByOrderByCreatedAtDesc();
    long countByStatus(SupportTicketStatus status);
    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
    List<SupportTicket> findByPassengerIdOrderByCreatedAtDesc(Long passengerId);

    List<SupportTicket> findByPassengerIdAndIssueTypeOrderByCreatedAtDesc(Long passengerId,
            com.premier.support.model.SupportTicketIssueType issueType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from SupportTicket t where t.id = :id")
    Optional<SupportTicket> findByIdForUpdate(@Param("id") Long id);
}
