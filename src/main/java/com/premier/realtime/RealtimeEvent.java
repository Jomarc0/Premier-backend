package com.premier.realtime;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Small, non-sensitive event envelope. Clients use the entity/id to refresh
 * only the data they are authorized to read; records are never broadcast here.
 */
public record RealtimeEvent(
        String eventId,
        String type,
        String entity,
        String entityId,
        Instant occurredAt,
        Map<String, String> metadata) {

    public static RealtimeEvent of(String type, String entity, Object entityId) {
        return new RealtimeEvent(UUID.randomUUID().toString(), type, entity,
                entityId == null ? null : String.valueOf(entityId), Instant.now(), Map.of());
    }
}
