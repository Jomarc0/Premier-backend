package com.premier.config;

import com.premier.repository.PassengerFcmTokenRepository;
import com.premier.service.FirebaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Sends fare change announcements to passenger devices registered for push notifications. */
@Service @RequiredArgsConstructor @Slf4j
public class FareConfigurationNotificationService {
    private static final int BATCH_SIZE = 500;
    private final PassengerFcmTokenRepository tokens;
    private final FirebaseService firebase;

    public void notifyAllPassengers(BigDecimal regularFare, BigDecimal discountedFare, Long version) {
        CompletableFuture.runAsync(() -> sendBatches(regularFare, discountedFare, version))
                .exceptionally(error -> {
                    log.warn("Fare update notification dispatch failed for version={}: {}", version, error.getClass().getSimpleName());
                    return null;
                });
    }

    private void sendBatches(BigDecimal regularFare, BigDecimal discountedFare, Long version) {
        var page = tokens.findDistinctFcmTokens(PageRequest.of(0, BATCH_SIZE));
        String title = "Premier fare update";
        String message = "New fares are active: regular PHP " + regularFare.toPlainString()
                + ", discounted PHP " + discountedFare.toPlainString() + ".";
        while (true) {
            List<String> destinations = page.getContent().stream()
                    .filter(token -> token != null && !token.isBlank()).toList();
            if (!destinations.isEmpty()) firebase.sendToMultiple(destinations, title, message);
            if (!page.hasNext()) break;
            page = tokens.findDistinctFcmTokens(page.nextPageable());
        }
        log.info("Fare update notification dispatch completed version={} registeredDevices={}", version,
                page.getTotalElements());
    }
}
