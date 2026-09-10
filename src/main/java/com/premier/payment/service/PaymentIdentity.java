package com.premier.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.premier.device.security.DevicePrincipal;
import com.premier.exception.ClientException;
import com.premier.response.FarePaymentResponse;
import com.premier.rfid.DeviceFareRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component @RequiredArgsConstructor
public class PaymentIdentity {
    private final ObjectMapper mapper;
    private final com.premier.payment.repository.PaymentIntentIdentityRepository identities;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void claim(String key, DeviceFareRequest request, DevicePrincipal device, String method) {
        if (key == null || key.length() < 12 || key.length() > 120) throw conflict();
        String offlineId = clean(request.getOfflineTransactionId());
        if (offlineId.length() > 120) throw conflict();
        var existing = identities.findById(key);
        if (existing.isEmpty() && !offlineId.isEmpty()) existing = identities.findByOfflineId(offlineId);
        if (existing.isPresent()) {
            var intent = existing.get();
            if (!intent.getId().equals(key) || !Objects.equals(clean(intent.getOfflineId()), offlineId)) throw conflict();
            verify(intent.getDeviceId(), intent.getFingerprint(), request, device, method);
            return;
        }
        var intent = new com.premier.payment.model.PaymentIntentIdentity();
        intent.setId(key);
        intent.setDeviceId(device.deviceId());
        intent.setFingerprint(fingerprint(request, device, method));
        intent.setOfflineId(offlineId.isEmpty() ? null : offlineId);
        intent.setCreatedAt(java.time.Instant.now());
        // Assigned IDs must INSERT, never merge over a concurrently committed identity.
        try { entityManager.persist(intent); entityManager.flush(); }
        catch (jakarta.persistence.PersistenceException ex) {
            if (ex instanceof org.hibernate.exception.ConstraintViolationException) throw conflict();
            throw ex;
        }
    }
    public String fingerprint(DeviceFareRequest request, DevicePrincipal device, String method) {
        if (request == null || device == null) throw conflict();
        String credential = switch(method) {
            case "QR" -> clean(request.getPayload()).replaceFirst("^PREMIER-FARE:", "");
            case "NFC" -> clean(request.getMobileNfcToken()).isEmpty()
                    ? clean(request.getPayload()).replaceFirst("^PREMIER-NFC:", "")
                    : clean(request.getMobileNfcToken()).replaceFirst("^PREMIER-NFC:", "");
            default -> clean(request.getRfidUid()).toUpperCase(Locale.ROOT);
        };
        try {
            // Transport nonce/time and offlineSync change during retry; intent fields do not.
            byte[] canonical = mapper.writeValueAsBytes(List.of("v2", device.deviceId(), method,
                    clean(request.getPlateNumber()).toUpperCase(Locale.ROOT), credential,
                    clean(request.getRequestId()), clean(request.getOfflineTransactionId()),
                    clean(request.getOfflineCapturedAt()), request.getFareAmount() == null ? ""
                        : Money.exact(request.getFareAmount()).toPlainString()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception ex) { throw new IllegalStateException("Unable to identify payment intent."); }
    }
    public void verify(String storedDevice, String storedFingerprint, DeviceFareRequest request, DevicePrincipal device, String method) {
        if (!Objects.equals(storedDevice, device.deviceId()) || storedFingerprint == null
                || !MessageDigest.isEqual(storedFingerprint.getBytes(StandardCharsets.UTF_8),
                        fingerprint(request, device, method).getBytes(StandardCharsets.UTF_8))) throw conflict();
    }
    public String snapshot(FarePaymentResponse response) {
        try { return snapshotMapper().writeValueAsString(response); }
        catch (Exception ex) { throw new IllegalStateException("Unable to persist payment result."); }
    }
    public FarePaymentResponse response(String snapshot) {
        if (snapshot == null) throw conflict();
        try { return snapshotMapper().readValue(snapshot, FarePaymentResponse.class); }
        catch (Exception ex) { throw new IllegalStateException("Unable to load payment result.", ex); }
    }
    public ClientException conflict() {
        return new ClientException(HttpStatus.CONFLICT, "CONFLICT", "This payment identity does not match its recorded intent. Request operator review.");
    }
    private ObjectMapper snapshotMapper() {
        // Storage format is independent of the public API's Manila-offset serializer.
        return new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .addMixIn(FarePaymentResponse.class, SnapshotTime.class)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    private abstract static class SnapshotTime {
        @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS")
        public abstract java.time.LocalDateTime getTimestamp();
        @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS")
        public abstract void setTimestamp(java.time.LocalDateTime value);
    }
    private String clean(String value) { return value == null ? "" : value.trim(); }
}
