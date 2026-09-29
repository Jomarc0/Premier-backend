package com.premier.response;

import com.premier.model.PassengerStatus;
import com.premier.model.PassengerCardCategory;
import lombok.Builder;
import lombok.Value;
import java.time.LocalDateTime;

/** Returned after an admin issues an RFID card. No passenger activation code is required. */
@Value
@Builder
public class CardIssuanceResponse {
    Long passengerId;
    String cardNumber;
    String rfidUid;
    PassengerCardCategory cardCategory;
    PassengerStatus status;
    LocalDateTime createdAt;
}
