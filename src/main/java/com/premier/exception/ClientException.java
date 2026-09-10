package com.premier.exception;

import org.springframework.http.HttpStatus;

/** Only explicitly authored, non-sensitive client messages belong here. */
public class ClientException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public ClientException(HttpStatus status, String code, String safeMessage) {
        super(safeMessage);
        this.status = status;
        this.code = code;
    }
    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
}
