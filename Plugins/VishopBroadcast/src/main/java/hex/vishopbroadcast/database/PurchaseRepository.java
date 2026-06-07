package hex.vishopbroadcast.database;

import hex.core.api.db.Db;
import hex.vishopbroadcast.config.VishopSettings;
import hex.vishopbroadcast.model.PurchaseInput;
import hex.vishopbroadcast.model.PurchaseRecord;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class PurchaseRepository {
    private final Db db;
    private final String playerTotalsTable;
    private final String purchaseLogsTable;
    private final String purchaseDedupeTable;
    private final boolean dedupeEnabled;
    private final int dedupeWindowSeconds;

    public PurchaseRepository(Db db, VishopSettings settings) {
        this.db = db;
        this.playerTotalsTable = db.t(sanitizeIdentifier(settings.playerTotalsTable(), "vishop_player_totals"));
        this.purchaseLogsTable = db.t(sanitizeIdentifier(settings.purchaseLogsTable(), "vishop_purchase_logs"));
        this.purchaseDedupeTable = db.t(sanitizeIdentifier(settings.purchaseDedupeTable(), "vishop_purchase_dedupe"));
        this.dedupeEnabled = settings.dedupeEnabled();
        this.dedupeWindowSeconds = settings.dedupeWindowSeconds();
    }

    public void ensureTables() {
        db.update("CREATE TABLE IF NOT EXISTS " + playerTotalsTable + " (" +
                "uuid VARCHAR(36) PRIMARY KEY," +
                "player_name VARCHAR(16) NOT NULL," +
                "total_spent DECIMAL(12,2) NOT NULL DEFAULT 0," +
                "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "INDEX idx_player_name (player_name)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

        db.update("CREATE TABLE IF NOT EXISTS " + purchaseLogsTable + " (" +
                "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                "external_id VARCHAR(128) NULL," +
                "purchase_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "service_key VARCHAR(64) NOT NULL," +
                "service_display VARCHAR(255) NOT NULL," +
                "player_uuid VARCHAR(36) NOT NULL," +
                "player_name VARCHAR(16) NOT NULL," +
                "amount VARCHAR(64) NULL," +
                "price DECIMAL(12,2) NULL," +
                "broadcast_info TEXT NOT NULL," +
                "created_by VARCHAR(64) NOT NULL," +
                "UNIQUE KEY uniq_external_id (external_id)," +
                "INDEX idx_purchase_time (purchase_time)," +
                "INDEX idx_id_time (id, purchase_time)," +
                "INDEX idx_player_uuid (player_uuid)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

        db.update("CREATE TABLE IF NOT EXISTS " + purchaseDedupeTable + " (" +
                "dedupe_key CHAR(64) PRIMARY KEY," +
                "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "expires_at TIMESTAMP NOT NULL," +
                "INDEX idx_expires_at (expires_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

        ensureExternalIdMigration();
    }

    public boolean savePurchase(PurchaseInput input) {
        return db.tx(tx -> {
            if (dedupeEnabled && input.externalId() == null) {
                tx.update("DELETE FROM " + purchaseDedupeTable + " WHERE dedupe_key=? AND expires_at <= NOW()", dedupeKey(input));
                int locked = tx.update(
                        "INSERT IGNORE INTO " + purchaseDedupeTable + " (dedupe_key, expires_at) " +
                                "VALUES (?, DATE_ADD(NOW(), INTERVAL " + dedupeWindowSeconds + " SECOND))",
                        dedupeKey(input)
                );
                if (locked <= 0) {
                    return false;
                }
            }

            int inserted = tx.update(
                    "INSERT IGNORE INTO " + purchaseLogsTable + " " +
                            "(external_id, service_key, service_display, player_uuid, player_name, amount, price, broadcast_info, created_by) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    input.externalId(),
                    input.serviceKey(),
                    input.serviceDisplay(),
                    input.playerUuid().toString(),
                    input.playerName(),
                    input.amount(),
                    input.price(),
                    input.broadcastInfo(),
                    input.createdBy()
            );

            if (inserted <= 0) {
                return false;
            }

            BigDecimal totalIncrement = input.price() == null ? BigDecimal.ZERO : input.price();
            tx.update(
                    "INSERT INTO " + playerTotalsTable + " (uuid, player_name, total_spent) VALUES (?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE player_name=VALUES(player_name), total_spent=total_spent+VALUES(total_spent)",
                    input.playerUuid().toString(), input.playerName(), totalIncrement
            );

            return true;
        });
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

    public int cleanupOlderThanDays(int retentionDays) {
        int safeDays = Math.max(1, retentionDays);
        int deletedLogs = db.update("DELETE FROM " + purchaseLogsTable + " WHERE purchase_time < (NOW() - INTERVAL " + safeDays + " DAY)");
        db.update("DELETE FROM " + purchaseDedupeTable + " WHERE expires_at <= NOW()");
        return deletedLogs;
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

    private void ensureExternalIdMigration() {
        try {
            db.update("ALTER TABLE " + purchaseLogsTable + " ADD COLUMN external_id VARCHAR(128) NULL AFTER id");
        } catch (RuntimeException ignored) {
            // Column already exists or database does not support AFTER; table created above already contains it for new installs.
        }
        try {
            db.update("CREATE UNIQUE INDEX uniq_external_id ON " + purchaseLogsTable + " (external_id)");
        } catch (RuntimeException ignored) {
            // Index already exists.
        }
    }

    private static String dedupeKey(PurchaseInput input) {
        String raw = String.join("|",
                normalize(input.playerName()),
                normalize(input.serviceKey()),
                normalize(input.amount()),
                normalizePrice(input.price())
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizePrice(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static String sanitizeIdentifier(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        if (!trimmed.matches("[A-Za-z0-9_]+")) {
            return fallback;
        }
        return trimmed;
    }
}

