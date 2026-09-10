package com.premier.payment.service;

import com.premier.exception.ClientException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    private Money() {}
    public static BigDecimal exact(BigDecimal value) {
        try {
            if (value == null) throw new ArithmeticException();
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new ClientException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "An exact amount with at most two decimal places is required.");
        }
    }
}
