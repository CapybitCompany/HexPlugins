package hex.events.persistence;

import hex.core.api.db.Db;
import hex.events.api.EventState;
import hex.events.model.EventInstance;
import hex.events.util.SnapshotCodec;

import java.util.List;
import java.util.UUID;

public final class EventInstanceRepository {
    private final Db db;
    public EventInstanceRepository(Db db) { this.db = db; }

    public void ensureTable() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("event_instances") + " (" +
                "instance_uuid CHAR(36) NOT NULL," +
                "event_id VARCHAR(96) NOT NULL," +
                "module_id VARCHAR(96) NOT NULL," +
                "occurrence_at BIGINT NOT NULL," +
                "registration_open_at BIGINT NOT NULL," +
                "prepare_at BIGINT NOT NULL," +
                "lobby_at BIGINT NOT NULL," +
                "start_at BIGINT NOT NULL," +
                "late_join_close_at BIGINT NOT NULL," +
                "end_at BIGINT NOT NULL," +
                "state VARCHAR(32) NOT NULL," +
                "prepared BOOLEAN NOT NULL DEFAULT FALSE," +
                "config_snapshot LONGTEXT NULL," +
                "last_error TEXT NULL," +
                "created_at BIGINT NOT NULL," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (instance_uuid)," +
                "KEY idx_event_start (event_id, start_at)," +
                "KEY idx_state_end (state, end_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    /** Captures all mutable runtime values before crossing the async boundary. */
    public static InstanceSnapshot snapshot(EventInstance i) {
        return new InstanceSnapshot(i.id(), i.definition().id(), i.definition().moduleId(),
                i.occurrenceAt().toEpochMilli(), i.registrationOpenAt().toEpochMilli(), i.prepareAt().toEpochMilli(),
                i.lobbyAt().toEpochMilli(), i.startAt().toEpochMilli(), i.lateJoinCloseAt().toEpochMilli(), i.endAt().toEpochMilli(),
                i.state(), i.prepared(), SnapshotCodec.encode(i.definition().snapshot()), i.lastError());
    }

    public static RuntimeSnapshot runtimeSnapshot(EventInstance i) {
        return new RuntimeSnapshot(i.id(), i.state(), i.prepared(), i.lastError());
    }

    public void insertIfAbsent(InstanceSnapshot s) {
        long now = System.currentTimeMillis();
        db.update("INSERT IGNORE INTO " + db.t("event_instances") +
                        " (instance_uuid,event_id,module_id,occurrence_at,registration_open_at,prepare_at,lobby_at,start_at,late_join_close_at,end_at,state,prepared,config_snapshot,last_error,created_at,updated_at)" +
                        " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                s.id().toString(), s.eventId(), s.moduleId(), s.occurrenceAt(), s.registrationOpenAt(), s.prepareAt(), s.lobbyAt(),
                s.startAt(), s.lateJoinCloseAt(), s.endAt(), s.state().name(), s.prepared(), s.configSnapshot(), s.lastError(), now, now);
    }

    /** Replaces a not-yet-frozen SCHEDULED occurrence with the latest config snapshot. */
    public void updateScheduledDefinition(InstanceSnapshot s) {
        db.update("UPDATE " + db.t("event_instances") + " SET module_id=?,occurrence_at=?,registration_open_at=?,prepare_at=?,lobby_at=?,start_at=?,late_join_close_at=?,end_at=?," +
                        "config_snapshot=?,prepared=FALSE,last_error='',updated_at=? WHERE instance_uuid=? AND state='SCHEDULED'",
                s.moduleId(), s.occurrenceAt(), s.registrationOpenAt(), s.prepareAt(), s.lobbyAt(), s.startAt(), s.lateJoinCloseAt(), s.endAt(),
                s.configSnapshot(), System.currentTimeMillis(), s.id().toString());
    }

    public void updateRuntime(RuntimeSnapshot s) {
        db.update("UPDATE " + db.t("event_instances") + " SET state=?, prepared=?, last_error=?, updated_at=? WHERE instance_uuid=?",
                s.state().name(), s.prepared(), s.lastError(), System.currentTimeMillis(), s.id().toString());
    }

    public void cancelForConfigChange(UUID instanceId, String reason) {
        db.update("UPDATE " + db.t("event_instances") + " SET state='CANCELLED', last_error=?, updated_at=? WHERE instance_uuid=? AND state NOT IN ('FINISHED','CANCELLED','FAILED')",
                reason, System.currentTimeMillis(), instanceId.toString());
    }

    public List<StoredInstance> loadNonTerminal() {
        return db.query("SELECT * FROM " + db.t("event_instances") +
                        " WHERE state NOT IN ('FINISHED','CANCELLED','FAILED') ORDER BY start_at ASC",
                rs -> new StoredInstance(
                        UUID.fromString(rs.getString("instance_uuid")), rs.getString("event_id"), rs.getString("module_id"),
                        rs.getLong("occurrence_at"), rs.getLong("registration_open_at"), rs.getLong("prepare_at"),
                        rs.getLong("lobby_at"), rs.getLong("start_at"), rs.getLong("late_join_close_at"), rs.getLong("end_at"),
                        EventState.valueOf(rs.getString("state")), rs.getBoolean("prepared"),
                        rs.getString("config_snapshot"), rs.getString("last_error")));
    }

    public record InstanceSnapshot(UUID id, String eventId, String moduleId,
                                   long occurrenceAt, long registrationOpenAt, long prepareAt, long lobbyAt,
                                   long startAt, long lateJoinCloseAt, long endAt, EventState state,
                                   boolean prepared, String configSnapshot, String lastError) { }

    public record RuntimeSnapshot(UUID id, EventState state, boolean prepared, String lastError) { }

    public record StoredInstance(UUID id, String eventId, String moduleId,
                                 long occurrenceAt, long registrationOpenAt, long prepareAt, long lobbyAt,
                                 long startAt, long lateJoinCloseAt, long endAt, EventState state,
                                 boolean prepared, String snapshot, String lastError) { }
}
