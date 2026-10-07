package com.premier.config;

import java.math.BigDecimal;

/** Official PHP amounts for new fares and newly issued passenger wallets. */
public final class FarePolicy {
    public static final BigDecimal REGULAR_FARE = new BigDecimal("75.00");
    public static final BigDecimal DISCOUNTED_FARE = new BigDecimal("60.00");
    public static final BigDecimal INITIAL_PASSENGER_BALANCE = new BigDecimal("150.00");

    private FarePolicy() {}
}
