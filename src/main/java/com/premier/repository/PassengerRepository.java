package com.premier.repository;

import com.premier.model.Passenger;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface PassengerRepository
        extends JpaRepository<Passenger, Long> {

    // Login uses cardNumber
    Optional<Passenger> findByCardNumber(String cardNumber);

    // Find by RFID uid (from card tap)
    Optional<Passenger> findByRfidUid(String rfidUid);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Passenger p where p.id = :id")
    Optional<Passenger> findLockedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Passenger p where p.rfidUid = :rfidUid")
    Optional<Passenger> findLockedByRfidUid(@Param("rfidUid") String rfidUid);

    boolean existsByCardNumber(String cardNumber);
    boolean existsByRfidUid(String rfidUid);
}
