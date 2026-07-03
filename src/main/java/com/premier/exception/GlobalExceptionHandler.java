package com.premier.exception;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import com.premier.response.ApiResponse;

import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(InvalidTotpException.class)
    public ResponseEntity<?> handleInvalidTotp(InvalidTotpException ex) {
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

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException ex) {
        log.warn("Handled runtime exception: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(safeRuntimeMessage(ex.getMessage())));
    }

    private String safeRuntimeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Unable to process request. Please try again.";
        }

        String trimmed = message.trim();
        if (isSafeClientMessage(trimmed)) {
            return trimmed;
        }

        return "Unable to process request. Please try again.";
    }

    private boolean isSafeClientMessage(String message) {
        return message.equals("Transaction not found.")
                || message.equals("Payment not yet completed.")
                || message.equals("Passenger not found.")
                || message.equals("QR fare token has already been used.")
                || message.equals("QR fare token expired. Please generate a new one.")
                || message.equals("Invalid QR fare token.")
                || message.equals("Invalid mobile NFC fare token.")
                || message.equals("Mobile NFC token has already been used.")
                || message.equals("Mobile NFC token expired. Please generate a new one.")
                || message.equals("Mobile NFC fare token is required.")
                || message.equals("QR fare token is required.")
                || message.equals("RFID UID is required.")
                || message.equals("Card not recognized. Please register your card.")
                || message.equals("Payment already processed recently. Please wait a moment.")
                || message.equals("Too many login attempts. Please try again later.")
                || message.startsWith("Account is ")
                || message.startsWith("Insufficient balance.");
    }
}
