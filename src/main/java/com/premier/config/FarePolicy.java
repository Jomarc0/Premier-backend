package com.premier.config;

import java.math.BigDecimal;

/** Initial balance for newly issued passenger wallets. Fare amounts are stored in PostgreSQL. */
public final class FarePolicy {
    public static final BigDecimal INITIAL_PASSENGER_BALANCE = new BigDecimal("150.00");

    private FarePolicy() {}
}
