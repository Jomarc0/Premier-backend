package com.premier.exception;


public class InvalidRfidException extends RuntimeException {
    public InvalidRfidException(String message) { super(message); }
}