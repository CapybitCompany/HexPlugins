package hex.events.persistence;

import hex.core.api.db.Db;
import hex.events.registration.EventQueuePriority;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class RegistrationRepository {
    private final Db db;
    public RegistrationRepository(Db db) { this.db = db; }

    public void ensureTables() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("event_registrations") + " (" +
                "instance_uuid CHAR(36) NOT NULL," +
                "player_uuid CHAR(36) NOT NULL," +
                "player_name VARCHAR(32) NULL," +
                "status VARCHAR(32) NOT NULL," +
                "registered_at BIGINT NOT NULL," +
                "cancelled_at BIGINT NULL," +
                "PRIMARY KEY (instance_uuid, player_uuid)," +
                "KEY idx_event_reg_player (player_uuid, status)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("event_participants") + " (" +
                "instance_uuid CHAR(36) NOT NULL," +
                "player_uuid CHAR(36) NOT NULL," +
                "joined_at BIGINT NOT NULL," +
                "left_at BIGINT NULL," +
                "status VARCHAR(32) NOT NULL," +
                "PRIMARY KEY (instance_uuid, player_uuid)," +
                "KEY idx_event_participant_player (player_uuid, status)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }


    /** Atomically persists the registration and its queue-priority snapshot. */
    public long upsertRegisteredWithAdmission(UUID instanceId, UUID playerId, String playerName, EventQueuePriority priority) {
        long now = System.currentTimeMillis();
        db.tx(tx -> {
            tx.update("INSERT INTO " + tx.t("event_registrations") +
                            " (instance_uuid,player_uuid,player_name,status,registered_at,cancelled_at) VALUES (?,?,?,?,?,NULL)" +
                            " ON DUPLICATE KEY UPDATE player_name=VALUES(player_name), status='REGISTERED', registered_at=VALUES(registered_at), cancelled_at=NULL",
                    instanceId.toString(), playerId.toString(), playerName, "REGISTERED", now);
            tx.update("INSERT INTO " + tx.t("event_admission") +
                            " (instance_uuid,player_uuid,player_name,registered_at,queue_priority,priority_name,status,reason,updated_at)" +
                            " VALUES (?,?,?,?,?,?,?,?,?)" +
                            " ON DUPLICATE KEY UPDATE player_name=VALUES(player_name), registered_at=VALUES(registered_at)," +
                            " queue_priority=VALUES(queue_priority), priority_name=VALUES(priority_name), status=VALUES(status)," +
                            " reason=VALUES(reason), updated_at=VALUES(updated_at)",
                    instanceId.toString(), playerId.toString(), playerName, now, priority.weight(), priority.name(),
                    "REGISTERED", "PRIORITY_SNAPSHOT_AT_REGISTRATION", now);
            return null;
        });
        return now;
    }
    public void markClosed(UUID instanceId, UUID playerId, String status) {
        db.update("UPDATE " + db.t("event_registrations") + " SET status=?, cancelled_at=? WHERE instance_uuid=? AND player_uuid=? AND status='REGISTERED'",
                status, System.currentTimeMillis(), instanceId.toString(), playerId.toString());
    }

    public void markCancelled(UUID instanceId, UUID playerId) { markClosed(instanceId, playerId, "CANCELLED"); }

    public void finalizeQueueRefund(UUID instanceId, UUID playerId) {
        db.update("UPDATE " + db.t("event_registrations") + " SET status='QUEUE_REFUNDED', cancelled_at=? WHERE instance_uuid=? AND player_uuid=? AND status='QUEUE_REFUND_PENDING'",
                System.currentTimeMillis(), instanceId.toString(), playerId.toString());
    }

    public List<RegistrationRow> loadActiveForInstances(List<UUID> instanceIds) {
        if (instanceIds.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(instanceIds.size(), "?"));
        Object[] args = instanceIds.stream().map(UUID::toString).toArray();
        return db.query("SELECT instance_uuid,player_uuid,player_name,status,registered_at FROM " + db.t("event_registrations") +
                        " WHERE status='REGISTERED' AND instance_uuid IN (" + placeholders + ")",
                rs -> new RegistrationRow(UUID.fromString(rs.getString("instance_uuid")), UUID.fromString(rs.getString("player_uuid")),
                        rs.getString("player_name"), rs.getLong("registered_at")), args);
    }

    public void markJoined(UUID instanceId, UUID playerId) {
        long now = System.currentTimeMillis();
        db.update("INSERT INTO " + db.t("event_participants") +
                        " (instance_uuid,player_uuid,joined_at,left_at,status) VALUES (?,?,?,NULL,'ACTIVE')" +
                        " ON DUPLICATE KEY UPDATE joined_at=IF(status='ACTIVE',joined_at,VALUES(joined_at)), left_at=NULL, status='ACTIVE'",
                instanceId.toString(), playerId.toString(), now);
    }

    public void markLeft(UUID instanceId, UUID playerId, String status) {
        db.update("UPDATE " + db.t("event_participants") + " SET left_at=?, status=? WHERE instance_uuid=? AND player_uuid=?",
                System.currentTimeMillis(), status, instanceId.toString(), playerId.toString());
    }

    public void markLeft(UUID instanceId, UUID playerId) { markLeft(instanceId, playerId, "LEFT"); }

    public List<ParticipantRow> loadActiveParticipants(List<UUID> instanceIds) {
        if (instanceIds.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(instanceIds.size(), "?"));
        Object[] args = instanceIds.stream().map(UUID::toString).toArray();
        return db.query("SELECT instance_uuid,player_uuid FROM " + db.t("event_participants") +
                        " WHERE status='ACTIVE' AND instance_uuid IN (" + placeholders + ")",
                rs -> new ParticipantRow(UUID.fromString(rs.getString("instance_uuid")), UUID.fromString(rs.getString("player_uuid"))), args);
    }

    public record RegistrationRow(UUID instanceId, UUID playerId, String playerName, long registeredAt) { }
    public record ParticipantRow(UUID instanceId, UUID playerId) { }
}
