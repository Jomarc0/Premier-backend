package com.premier.staffcash.repository;

import com.premier.staffcash.model.StaffCashCard;
import com.premier.staffcash.model.StaffCashCardPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StaffCashCardRepository extends JpaRepository<StaffCashCard, Long> {
    Optional<StaffCashCard> findByRfidUid(String rfidUid);

    @Query(value = """
            select * from staff_cash_cards
            where regexp_replace(replace(upper(rfid_uid), 'UID', ''), '[^A-F0-9]', '', 'g') = :uid
            limit 1
            """, nativeQuery = true)
    Optional<StaffCashCard> findByNormalizedRfidUid(@Param("uid") String uid);
    Optional<StaffCashCard> findByStaffIdAndPurpose(Long staffId, StaffCashCardPurpose purpose);
    List<StaffCashCard> findAllByOrderByRegisteredAtDesc();
}
