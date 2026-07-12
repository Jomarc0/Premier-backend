package com.premier.staffcash.repository;

import com.premier.staffcash.model.StaffCashCard;
import com.premier.staffcash.model.StaffCashCardPurpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StaffCashCardRepository extends JpaRepository<StaffCashCard, Long> {
    Optional<StaffCashCard> findByRfidUid(String rfidUid);
    Optional<StaffCashCard> findByStaffIdAndPurpose(Long staffId, StaffCashCardPurpose purpose);
    List<StaffCashCard> findAllByOrderByRegisteredAtDesc();
}
