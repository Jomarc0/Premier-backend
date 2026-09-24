package com.premier.trip.request;

import com.premier.trip.model.TripDirection;
import jakarta.validation.constraints.NotNull;

public record StartTripRequest(@NotNull TripDirection direction) {}
