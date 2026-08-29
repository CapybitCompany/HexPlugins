package hex.events.persistence;

import hex.core.api.db.Db;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Public-facing, denormalized 7-day event schedule snapshot.
 *
 * The table is intentionally small and is replaced atomically in a single
 * transaction so external HTTP/API consumers always see either the previous
 * complete snapshot or the new complete snapshot, never a half-refreshed one.
 */
public final class EventSchedule7dRepository {
    public static final String TABLE = "event_schedule_7d";

    private final Db db;

    public EventSchedule7dRepository(Db db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    public void ensureTable() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t(TABLE) + " (" +
                "instance_uuid CHAR(36) NOT NULL," +
                "event_id VARCHAR(96) NOT NULL," +
                "event_name VARCHAR(255) NOT NULL," +
                "scheduled_at DATETIME NOT NULL," +
                "scheduled_at_epoch_seconds BIGINT NOT NULL," +
                "timezone VARCHAR(64) NOT NULL," +
                "schedule_type VARCHAR(16) NOT NULL," +
                "is_recurring BOOLEAN NOT NULL," +
                "published_at DATETIME NOT NULL," +
                "published_at_epoch_seconds BIGINT NOT NULL," +
                "PRIMARY KEY (instance_uuid)," +
                "KEY idx_schedule_time (scheduled_at_epoch_seconds)," +
                "KEY idx_event_id (event_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    /**
     * Atomically replaces the complete public 7-day window.
     */
    public void replaceAll(List<ScheduleRow> rows, Instant publishedAt, ZoneId displayZone) {
        List<ScheduleRow> safeRows = rows == null ? List.of() : List.copyOf(rows);
        Instant publicationInstant = Objects.requireNonNull(publishedAt, "publishedAt");
        ZoneId zone = Objects.requireNonNull(displayZone, "displayZone");
        Timestamp publishedTimestamp = Timestamp.valueOf(LocalDateTime.ofInstant(publicationInstant, zone).withNano(0));
        long publishedEpochSeconds = publicationInstant.getEpochSecond();

        db.tx(tx -> {
            tx.update("DELETE FROM " + tx.t(TABLE));
            if (!safeRows.isEmpty()) {
                List<Object[]> params = safeRows.stream().map(row -> new Object[]{
                        row.instanceId().toString(),
                        row.eventId(),
                        row.eventName(),
                        Timestamp.valueOf(row.scheduledAt().withNano(0)),
                        row.scheduledAtEpochSeconds(),
                        row.timezone(),
                        row.scheduleType(),
                        row.recurring(),
                        publishedTimestamp,
                        publishedEpochSeconds
                }).toList();
                tx.batch("INSERT INTO " + tx.t(TABLE) +
                                " (instance_uuid,event_id,event_name,scheduled_at,scheduled_at_epoch_seconds,timezone,schedule_type,is_recurring,published_at,published_at_epoch_seconds)" +
                                " VALUES (?,?,?,?,?,?,?,?,?,?)",
                        params);
            }
            return null;
        });
    }

    public record ScheduleRow(
            UUID instanceId,
            String eventId,
            String eventName,
            LocalDateTime scheduledAt,
            long scheduledAtEpochSeconds,
            String timezone,
            String scheduleType,
            boolean recurring
    ) {
        public ScheduleRow {
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(eventName, "eventName");
            Objects.requireNonNull(scheduledAt, "scheduledAt");
            Objects.requireNonNull(timezone, "timezone");
            Objects.requireNonNull(scheduleType, "scheduleType");
        }
    }
}
