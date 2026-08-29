package hex.events.registration;

import java.util.UUID;

public record AdmissionEntry(
        UUID playerId,
        String playerName,
        long registeredAt,
        EventQueuePriority priority,
        AdmissionStatus status,
        String reason,
        long updatedAt
) {
    public AdmissionEntry {
        playerName = playerName == null ? "" : playerName;
        priority = priority == null ? EventQueuePriority.NORMAL : priority;
        status = status == null ? AdmissionStatus.REGISTERED : status;
        reason = reason == null ? "" : reason;
    }

    public AdmissionEntry withStatus(AdmissionStatus next, String nextReason) {
        return new AdmissionEntry(playerId, playerName, registeredAt, priority, next, nextReason, System.currentTimeMillis());
    }

    public AdmissionEntry withPriority(EventQueuePriority nextPriority, AdmissionStatus nextStatus, String nextReason) {
        return new AdmissionEntry(playerId, playerName, registeredAt, nextPriority, nextStatus, nextReason, System.currentTimeMillis());
    }
}
