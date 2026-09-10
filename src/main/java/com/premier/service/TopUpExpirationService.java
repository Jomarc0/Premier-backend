package com.premier.service;

import com.premier.model.TopUpRequest;
import com.premier.model.TransactionStatus;
import com.premier.repository.TopUpRequestRepository;
import com.premier.admin.repository.ActivityLogRepository;
import com.premier.admin.model.Admin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TopUpExpirationService {

    private final TopUpRequestRepository topUpRequestRepository;
    private final PayMongoService payMongoService;
    private final ActivityLogRepository activityLogs;

    /**
     * Runs every minute to find and expire pending top-ups whose expiration time has passed.
     * Before expiring, verifies the top-up has not already been paid.
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    @Transactional
    public void expirePendingTopUps() {
        LocalDateTime now = LocalDateTime.now();

        // 1. Top-ups with explicit expiration that has passed
        List<TopUpRequest> expiredTopUps = topUpRequestRepository
                .findByStatusAndExpiresAtBefore(TransactionStatus.PENDING, now);

        // 2. Legacy top-ups created before expiration feature (expires_at IS NULL)
        List<TopUpRequest> legacyTopUps = topUpRequestRepository
                .findByStatusAndExpiresAtIsNull(TransactionStatus.PENDING);

        List<TopUpRequest> allToExpire = new java.util.ArrayList<>(expiredTopUps);
        allToExpire.addAll(legacyTopUps);

        if (allToExpire.isEmpty()) {
            return;
        }

        log.info("TOPUP_EXPIRATION_BATCH found={} (timed={} legacy={})",
                allToExpire.size(), expiredTopUps.size(), legacyTopUps.size());

        for (TopUpRequest request : allToExpire) {
            try {
                expireTopUp(request);
            } catch (Exception ex) {
                log.warn("TOPUP_EXPIRATION_FAILED id={} ref={} type={}",
                        request.getId(), request.getReferenceNumber(), ex.getClass().getSimpleName());
            }
        }
    }

    private void expireTopUp(TopUpRequest request) {
        // Re-read with lock to prevent race with webhook/verify
        TopUpRequest locked = topUpRequestRepository.findLockedById(request.getId()).orElse(null);
        if (locked == null) return;

        // Double-check status hasn't changed (webhook may have just completed it)
        if (locked.getStatus() != TransactionStatus.PENDING) {
            return;
        }

        // Double-check expiration time
        if (locked.getExpiresAt() != null && locked.getExpiresAt().isAfter(LocalDateTime.now())) {
            return;
        }

        // Attempt to expire the PayMongo checkout session
        if (locked.getPaymongoLinkId() != null) {
            payMongoService.expireCheckoutSession(locked.getPaymongoLinkId());
        }

        // Update status to EXPIRED
        locked.setStatus(TransactionStatus.EXPIRED);
        locked.setLastSafeError("EXPIRED");
        topUpRequestRepository.saveAndFlush(locked);

        // Audit log
        activityLogs.save(com.premier.admin.model.ActivityLog.builder()
                .admin(null)
                .action("TOPUP_EXPIRED")
                .targetType("TOPUP")
                .targetId(locked.getId())
                .details("Top-up automatically expired: " + locked.getReferenceNumber()
                        + " | Amount: " + locked.getAmount()
                        + " | Passenger: " + locked.getPassenger().getId()
                        + " | ExpiresAt: " + locked.getExpiresAt())
                .build());

        log.info("TOPUP_EXPIRED id={} ref={} passenger={} amount={}",
                locked.getId(), locked.getReferenceNumber(), locked.getPassenger().getId(), locked.getAmount());
    }

    /**
     * Called when a passenger attempts to create a new top-up.
     * Checks if they have an existing pending top-up that has already expired.
     * If so, expires it immediately and allows the new top-up.
     *
     * @param passengerId the passenger's ID
     * @return true if an expired top-up was found and expired, false otherwise
     */
    @Transactional
    public boolean expireIfPendingAndExpired(Long passengerId) {
        List<TopUpRequest> pending = topUpRequestRepository
                .findByPassengerIdAndStatus(passengerId, TransactionStatus.PENDING);

        if (pending.isEmpty()) return false;

        LocalDateTime now = LocalDateTime.now();
        boolean anyExpired = false;
        for (TopUpRequest request : pending) {
            if (request.getExpiresAt() == null || request.getExpiresAt().isBefore(now)) {
                expireTopUp(request);
                anyExpired = true;
            }
        }
        return anyExpired;
    }
}