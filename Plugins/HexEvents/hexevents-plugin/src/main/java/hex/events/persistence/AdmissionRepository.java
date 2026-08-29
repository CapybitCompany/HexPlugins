package hex.events.persistence;

import hex.core.api.db.Db;
import hex.events.registration.AdmissionEntry;
import hex.events.registration.AdmissionStatus;
import hex.events.registration.EventQueuePriority;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class AdmissionRepository {
    private final Db db;

    public AdmissionRepository(Db db) { this.db = db; }

    public void ensureTable() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("event_admission") + " (" +
                "instance_uuid CHAR(36) NOT NULL," +
                "player_uuid CHAR(36) NOT NULL," +
                "player_name VARCHAR(32) NULL," +
                "registered_at BIGINT NOT NULL," +
                "queue_priority INT NOT NULL DEFAULT 0," +
                "priority_name VARCHAR(24) NOT NULL DEFAULT 'NORMAL'," +
                "status VARCHAR(32) NOT NULL," +
                "reason VARCHAR(128) NULL," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (instance_uuid, player_uuid)," +
                "KEY idx_event_admission_status (instance_uuid, status)," +
                "KEY idx_event_admission_player (player_uuid, status)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    public void upsert(UUID instanceId, AdmissionEntry entry) {
        db.update("INSERT INTO " + db.t("event_admission") +
                        " (instance_uuid,player_uuid,player_name,registered_at,queue_priority,priority_name,status,reason,updated_at)" +
                        " VALUES (?,?,?,?,?,?,?,?,?)" +
                        " ON DUPLICATE KEY UPDATE player_name=VALUES(player_name), registered_at=VALUES(registered_at)," +
                        " queue_priority=VALUES(queue_priority), priority_name=VALUES(priority_name), status=VALUES(status)," +
                        " reason=VALUES(reason), updated_at=VALUES(updated_at)",
                instanceId.toString(), entry.playerId().toString(), entry.playerName(), entry.registeredAt(),
                entry.priority().weight(), entry.priority().name(), entry.status().name(), entry.reason(), entry.updatedAt());
    }

    public List<AdmissionRow> loadForInstances(List<UUID> instanceIds) {
        if (instanceIds.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(instanceIds.size(), "?"));
        Object[] args = instanceIds.stream().map(UUID::toString).toArray();
        return db.query("SELECT instance_uuid,player_uuid,player_name,registered_at,queue_priority,priority_name,status,reason,updated_at FROM " + db.t("event_admission") +
                        " WHERE instance_uuid IN (" + placeholders + ")",
                rs -> {
                    EventQueuePriority priority;
                    try { priority = EventQueuePriority.valueOf(rs.getString("priority_name")); }
                    catch (Exception ignored) { priority = EventQueuePriority.NORMAL; }
                    AdmissionStatus status;
                    try { status = AdmissionStatus.valueOf(rs.getString("status")); }
                    catch (Exception ignored) { status = AdmissionStatus.REGISTERED; }
                    AdmissionEntry entry = new AdmissionEntry(UUID.fromString(rs.getString("player_uuid")), rs.getString("player_name"),
                            rs.getLong("registered_at"), priority, status, rs.getString("reason"), rs.getLong("updated_at"));
                    return new AdmissionRow(UUID.fromString(rs.getString("instance_uuid")), entry);
                }, args);
    }

    public void finalizePendingRefund(UUID instanceId, UUID playerId) {
        db.update("UPDATE " + db.t("event_admission") + " SET status='QUEUE_REFUNDED', reason='QUEUE_REFUNDED_CAPACITY', updated_at=?" +
                        " WHERE instance_uuid=? AND player_uuid=? AND status='QUEUE_REFUND_PENDING'",
                System.currentTimeMillis(), instanceId.toString(), playerId.toString());
    }

    public record AdmissionRow(UUID instanceId, AdmissionEntry entry) { }
}
