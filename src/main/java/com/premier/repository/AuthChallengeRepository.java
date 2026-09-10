package com.premier.repository;

import com.premier.model.AuthChallenge;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthChallengeRepository extends JpaRepository<AuthChallenge, String> {
    boolean existsBySupportTicketId(Long supportTicketId);
}
