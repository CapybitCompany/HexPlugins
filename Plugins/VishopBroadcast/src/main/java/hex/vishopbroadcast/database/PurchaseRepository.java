package hex.vishopbroadcast.database;

import hex.core.api.db.Db;
import hex.vishopbroadcast.config.VishopSettings;
import hex.vishopbroadcast.model.PurchaseRecord;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Read-only view of the queue written by VishopBroadcastProxy. */
public final class PurchaseRepository {
    private final Db db;
    private final String purchaseLogsTable;

    public PurchaseRepository(Db db, VishopSettings settings) {
        this.db = db;
        this.purchaseLogsTable = db.t(sanitizeIdentifier(settings.purchaseLogsTable(), "vishop_purchase_logs"));
    }

    public long maxLogId() {
        return db.queryOne("SELECT COALESCE(MAX(id), 0) AS max_id FROM " + purchaseLogsTable, rs -> rs.getLong("max_id"))
                .orElse(0L);
    }

    public List<PurchaseRecord> findAfter(long lastSeenId, int limit) {
        return db.query(
                "SELECT id, external_id, purchase_time, service_key, service_display, player_uuid, player_name, amount, price, broadcast_info, created_by " +
                        "FROM " + purchaseLogsTable + " WHERE id > ? ORDER BY id ASC LIMIT ?",
                this::mapRecord,
                lastSeenId,
                Math.max(1, limit)
        );
    }

    private PurchaseRecord mapRecord(ResultSet rs) throws SQLException {
        Timestamp timestamp = rs.getTimestamp("purchase_time");
        LocalDateTime purchaseTime = timestamp == null ? LocalDateTime.now() : timestamp.toLocalDateTime();
        String uuidRaw = rs.getString("player_uuid");
        UUID uuid = uuidRaw == null || uuidRaw.isBlank() ? null : UUID.fromString(uuidRaw);
        return new PurchaseRecord(
                rs.getLong("id"),
                rs.getString("external_id"),
                purchaseTime,
                rs.getString("service_key"),
                rs.getString("service_display"),
                uuid,
                rs.getString("player_name"),
                rs.getString("amount"),
                rs.getBigDecimal("price"),
                rs.getString("broadcast_info"),
                rs.getString("created_by")
        );
    }

    private static String sanitizeIdentifier(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.matches("[A-Za-z0-9_]+") ? trimmed : fallback;
    }
}
