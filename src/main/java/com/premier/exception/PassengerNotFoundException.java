package com.premier.exception;

public class PassengerNotFoundException extends RuntimeException {
    public PassengerNotFoundException(String message) { super(message); }
}