package com.premier.exception;

import org.hibernate.LazyInitializationException;
import org.hibernate.exception.JDBCConnectionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.AccessDeniedException;

import org.springframework.web.client.*;

import com.premier.response.ApiResponse;

import java.util.stream.Collectors;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ClientException.class)
    public ResponseEntity<?> handleClient(ClientException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.builder()
                .success(false).code(ex.getCode()).message(ex.getMessage())
                .reference(com.premier.security.RequestCorrelationFilter.reference()).build());
    }

    @ExceptionHandler(HttpMessageNotWritableException.class)
    public ResponseEntity<?> handleNotWritable(HttpMessageNotWritableException ex) {
        return safeFailure(HttpStatus.INTERNAL_SERVER_ERROR, "RESPONSE_UNAVAILABLE", "Unable to complete the request.", ex);
    }

    @ExceptionHandler({
            CannotGetJdbcConnectionException.class,
            DataAccessResourceFailureException.class,
            JDBCConnectionException.class,
            CannotAcquireLockException.class
    })
    public ResponseEntity<?> handleConnectionFailure(Exception ex) {
        return safeFailure(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Service is temporarily unavailable. Retry the original request identity.", ex);
    }

    @ExceptionHandler({org.springframework.web.client.ResourceAccessException.class,
            org.springframework.web.client.RestClientException.class})
    public ResponseEntity<?> handleRestClientFailure(Exception ex) {
        return safeFailure(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Payment provider is temporarily unavailable. Please try again later.", ex);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> handleDataIntegrity(DataIntegrityViolationException ex) {
        return safeFailure(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION", "Data integrity constraint violated. Please retry.", ex);
    }

    @ExceptionHandler(LazyInitializationException.class)
    public ResponseEntity<?> handleLazyInit(LazyInitializationException ex) {
        return safeFailure(HttpStatus.INTERNAL_SERVER_ERROR, "RESPONSE_UNAVAILABLE", "Unable to complete the request.", ex);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> handleDataAccess(DataAccessException ex) {
        return safeFailure(HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT_UNKNOWN", "Unable to complete the request. Retry the original request identity.", ex);
    }

    @ExceptionHandler(PassengerNotFoundException.class)
    public ResponseEntity<?> handleNotFound(PassengerNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(InvalidRfidException.class)
    public ResponseEntity<?> handleInvalidRfid(InvalidRfidException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Access denied."));
    }

    @ExceptionHandler(InvalidTotpException.class)
    public ResponseEntity<?> handleInvalidTotp(InvalidTotpException ex) {
        if (ex.getRetryAfterSeconds() != null) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error(
                            ex.getMessage(),
                            Map.of("retryAfterSeconds", ex.getRetryAfterSeconds())));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(InvalidBiometricTokenException.class)
    public ResponseEntity<?> handleInvalidBiometricToken(InvalidBiometricTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors()
                .stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(errors));
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<?> handleMalformedRequest(Exception ex) {
        return safeFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request fields are missing or invalid.", ex);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethod(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        return safeFailure(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "This request method is not supported.", ex);
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> handleMediaType(Exception ex) {
        return safeFailure(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Use the supported request content type.", ex);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException ex) {
        return safeFailure(HttpStatus.INTERNAL_SERVER_ERROR, "SERVICE_UNAVAILABLE", "Unable to complete the request. Retry the original request identity.", ex);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<?> handleSecurity(SecurityException ex) {
        return safeFailure(HttpStatus.FORBIDDEN, "DEVICE_REJECTED", "Device authorization or request freshness could not be verified.", ex);
    }

    private ResponseEntity<?> safeFailure(HttpStatus status, String code, String message, Exception ex) {
        String reference = com.premier.security.RequestCorrelationFilter.reference();
        log.warn("Request failure reference={} type={}", reference, ex.getClass().getName());
        return ResponseEntity.status(status).body(ApiResponse.builder().success(false).code(code).message(message).reference(reference).build());
    }

}
