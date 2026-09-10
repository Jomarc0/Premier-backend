package com.premier.response;

import com.premier.model.PassengerStatus;
import lombok.Builder;
import lombok.Value;

/** Returned after an admin issues an RFID card. No passenger activation code is required. */
@Value
@Builder
public class CardIssuanceResponse {
    Long passengerId;
    String cardNumber;
    PassengerStatus status;
}
