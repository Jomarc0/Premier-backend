package com.premier.payment.service;
import com.premier.exception.ClientException;
import org.springframework.http.HttpStatus;
import java.time.*;
import lombok.extern.slf4j.Slf4j;
@Slf4j
public final class CaptureTime {
    private CaptureTime() {}
    public static LocalDateTime optional(String value) {
        if(value==null || value.isBlank()) return null;
        try {
            Instant at=Instant.parse(value.trim()); Instant now=Instant.now();
            if(at.isAfter(now.plusSeconds(10))||at.isBefore(now.minus(Duration.ofDays(7)))) {
                log.warn("[PAYMENT] capture_time_rejected reason={} capturedAt={} serverNow={} skewSeconds={}",
                        at.isAfter(now) ? "FUTURE" : "TOO_OLD", at, now, Duration.between(now, at).getSeconds());
                throw new IllegalArgumentException();
            }
            return LocalDateTime.ofInstant(at,ZoneId.of("Asia/Manila"));
        } catch(RuntimeException invalid) {
            if (invalid instanceof java.time.format.DateTimeParseException) {
                // Never print an unparsed request value: it may contain sensitive or injected text.
                log.warn("[PAYMENT] capture_time_rejected reason=INVALID_FORMAT");
            }
            throw new ClientException(HttpStatus.CONFLICT,"RECONCILIATION_REQUIRED","Capture time cannot be trusted. Operator reconciliation is required.");
        }
    }
}
