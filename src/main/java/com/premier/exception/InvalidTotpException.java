package com.premier.exception;

public class InvalidTotpException extends RuntimeException {
    private final Long retryAfterSeconds;

    public InvalidTotpException(String message) {
        this(message, null);
    }

    public InvalidTotpException(String message, Long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
