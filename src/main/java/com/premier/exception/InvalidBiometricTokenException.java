package com.premier.exception;

public class InvalidBiometricTokenException extends RuntimeException {
    public InvalidBiometricTokenException(String message) {
        super(message);
    }
}
