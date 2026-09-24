package com.premier.trip.model;

public enum TripDirection {
    SM_TO_GRAND("SM Terminal", "Grand Terminal", "SM Terminal to Grand Terminal"),
    GRAND_TO_SM("Grand Terminal", "SM Terminal", "Grand Terminal to SM Terminal");

    private final String origin;
    private final String destination;
    private final String routeLabel;

    TripDirection(String origin, String destination, String routeLabel) {
        this.origin = origin;
        this.destination = destination;
        this.routeLabel = routeLabel;
    }

    public String origin() { return origin; }
    public String destination() { return destination; }
    public String routeLabel() { return routeLabel; }
}
