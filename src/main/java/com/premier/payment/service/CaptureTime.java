package com.premier.payment.service;
import com.premier.exception.ClientException;
import org.springframework.http.HttpStatus;
import java.time.*;
public final class CaptureTime {
    private CaptureTime() {}
    public static LocalDateTime optional(String value) {
        if(value==null || value.isBlank()) return null;
        try {
            Instant at=Instant.parse(value.trim()); Instant now=Instant.now();
            if(at.isAfter(now.plusSeconds(10))||at.isBefore(now.minus(Duration.ofDays(7)))) throw new IllegalArgumentException();
            return LocalDateTime.ofInstant(at,ZoneId.of("Asia/Manila"));
        } catch(RuntimeException invalid) {
            throw new ClientException(HttpStatus.CONFLICT,"RECONCILIATION_REQUIRED","Capture time cannot be trusted. Operator reconciliation is required.");
        }
    }
}
