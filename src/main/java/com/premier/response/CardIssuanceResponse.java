package com.premier.response;

import com.premier.model.PassengerStatus;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** Returned only to the authenticated issuer so the one-time code can be handed to the card owner. */
@Value
@Builder
public class CardIssuanceResponse {
    Long passengerId;
    String cardNumber;
    PassengerStatus status;
    String activationCode;
    LocalDateTime activationExpiresAt;
}
