package com.premier.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Centralized server-side publisher for cross-client data synchronization. */
@Service
@RequiredArgsConstructor
public class RealtimeEventPublisher {

    public static final String ADMIN_DESTINATION = "/topic/admin/realtime";
    public static final String STAFF_DESTINATION = "/topic/staff/realtime";

    private final SimpMessagingTemplate messagingTemplate;

    public void admin(String type, String entity, Object entityId) {
        RealtimeEvent event = RealtimeEvent.of(type, entity, entityId);
        afterCommit(() -> messagingTemplate.convertAndSend(ADMIN_DESTINATION, event));
    }

    public void passenger(Long passengerId, String type, String entity, Object entityId) {
        if (passengerId != null) {
            RealtimeEvent event = RealtimeEvent.of(type, entity, entityId);
            afterCommit(() -> messagingTemplate.convertAndSendToUser(String.valueOf(passengerId), "/queue/realtime", event));
        }
    }

    public void staff(String type, String entity, Object entityId) {
        RealtimeEvent event = RealtimeEvent.of(type, entity, entityId);
        afterCommit(() -> messagingTemplate.convertAndSend(STAFF_DESTINATION, event));
    }

    public void adminAndPassenger(Long passengerId, String type, String entity, Object entityId) {
        admin(type, entity, entityId);
        passenger(passengerId, type, entity, entityId);
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
            return;
        }
        action.run();
    }
}
