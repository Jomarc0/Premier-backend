package com.premier.staffqueue.model;

public enum BusQueueStatus {
    WAITING("Waiting"),
    BOARDING("Boarding"),
    CANCELLED("Cancelled"),
    GPS_UNKNOWN("GPS unavailable"),
    AT_TERMINAL("At Terminal"),
    DEPARTED("Departed"),
    ON_ROUTE("On Route"),
    NEAR_TERMINAL("Near Terminal"),
    ARRIVING("Arriving"),
    ARRIVED("Arrived");

    private final String label;

    BusQueueStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
