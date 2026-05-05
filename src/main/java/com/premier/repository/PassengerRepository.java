package com.premier.repository;

import com.premier.model.Passenger;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PassengerRepository
        extends JpaRepository<Passenger, Long> {

    // Login uses cardNumber
    Optional<Passenger> findByCardNumber(String cardNumber);

    // Find by RFID uid (from card tap)
    Optional<Passenger> findByRfidUid(String rfidUid);

    boolean existsByCardNumber(String cardNumber);
    boolean existsByRfidUid(String rfidUid);
}