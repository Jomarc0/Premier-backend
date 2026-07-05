package com.premier.cardfreeze.request;

import com.premier.cardfreeze.model.CardFreezeRequestType;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CardFreezeSubmitRequest {
    private CardFreezeRequestType requestType = CardFreezeRequestType.FREEZE_REQUEST;

    @Size(max = 1000, message = "Reason must be 1000 characters or less.")
    private String reason;
}
