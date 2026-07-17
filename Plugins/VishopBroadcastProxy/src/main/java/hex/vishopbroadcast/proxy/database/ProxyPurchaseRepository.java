package hex.vishopbroadcast.proxy.database;

import hex.vishopbroadcast.proxy.config.ProxySettings;
import hex.vishopbroadcast.proxy.model.PurchaseInput;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class ProxyPurchaseRepository {
    private final DataSource dataSource;
    private final String playerTotalsTable;
    private final String purchaseLogsTable;
    private final String purchaseDedupeTable;
    private final boolean dedupeEnabled;
    private final int dedupeWindowSeconds;

    public ProxyPurchaseRepository(DataSource dataSource, ProxySettings settings) {
        this.dataSource = dataSource;
        this.playerTotalsTable = settings.tables().playerTotals();
        this.purchaseLogsTable = settings.tables().purchaseLogs();
        this.purchaseDedupeTable = settings.tables().purchaseDedupe();
        this.dedupeEnabled = settings.dedupe().enabled();
        this.dedupeWindowSeconds = settings.dedupe().windowSeconds();
    }

    public void ensureTables() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + playerTotalsTable + " (" +
                    "uuid VARCHAR(36) PRIMARY KEY," +
                    "player_name VARCHAR(16) NOT NULL," +
                    "total_spent DECIMAL(12,2) NOT NULL DEFAULT 0," +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "INDEX idx_player_name (player_name)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + purchaseLogsTable + " (" +
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
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + purchaseDedupeTable + " (" +
                    "dedupe_key CHAR(64) PRIMARY KEY," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "expires_at TIMESTAMP NOT NULL," +
                    "INDEX idx_expires_at (expires_at)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        }
        ensureExternalIdMigration();
    }

    public Optional<UUID> findKnownUuid(String playerName) throws SQLException {
        String sql = "SELECT uuid FROM " + playerTotalsTable + " WHERE LOWER(player_name)=LOWER(?) ORDER BY updated_at DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(UUID.fromString(result.getString("uuid")));
                } catch (IllegalArgumentException ignored) {
                    return Optional.empty();
                }
            }
        }
    }

    public boolean savePurchase(PurchaseInput input) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (dedupeEnabled && input.externalId() == null && !acquireDedupeLock(connection, input)) {
                    connection.rollback();
                    return false;
                }

                int inserted;
                String insertLog = "INSERT IGNORE INTO " + purchaseLogsTable + " " +
                        "(external_id, service_key, service_display, player_uuid, player_name, amount, price, broadcast_info, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(insertLog)) {
                    statement.setString(1, input.externalId());
                    statement.setString(2, input.serviceKey());
                    statement.setString(3, input.serviceDisplay());
                    statement.setString(4, input.playerUuid().toString());
                    statement.setString(5, input.playerName());
                    statement.setString(6, input.amount());
                    statement.setBigDecimal(7, input.price());
                    statement.setString(8, input.broadcastInfo());
                    statement.setString(9, input.createdBy());
                    inserted = statement.executeUpdate();
                }
                if (inserted <= 0) {
                    connection.rollback();
                    return false;
                }

                String updateTotal = "INSERT INTO " + playerTotalsTable + " (uuid, player_name, total_spent) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE player_name=VALUES(player_name), total_spent=total_spent+VALUES(total_spent)";
                try (PreparedStatement statement = connection.prepareStatement(updateTotal)) {
                    statement.setString(1, input.playerUuid().toString());
                    statement.setString(2, input.playerName());
                    statement.setBigDecimal(3, input.price() == null ? BigDecimal.ZERO : input.price());
                    statement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public int cleanupOlderThanDays(int retentionDays) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            int deleted;
            try (Statement statement = connection.createStatement()) {
                deleted = statement.executeUpdate("DELETE FROM " + purchaseLogsTable + " WHERE purchase_time < (NOW() - INTERVAL " + Math.max(1, retentionDays) + " DAY)");
                statement.executeUpdate("DELETE FROM " + purchaseDedupeTable + " WHERE expires_at <= NOW()");
            }
            return deleted;
        }
    }

    private boolean acquireDedupeLock(Connection connection, PurchaseInput input) throws SQLException {
        String key = dedupeKey(input);
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + purchaseDedupeTable + " WHERE dedupe_key=? AND expires_at <= NOW()")) {
            delete.setString(1, key);
            delete.executeUpdate();
        }
        String insert = "INSERT IGNORE INTO " + purchaseDedupeTable + " (dedupe_key, expires_at) VALUES (?, DATE_ADD(NOW(), INTERVAL " + dedupeWindowSeconds + " SECOND))";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, key);
            return statement.executeUpdate() > 0;
        }
    }

    private void ensureExternalIdMigration() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try {
                statement.executeUpdate("ALTER TABLE " + purchaseLogsTable + " ADD COLUMN external_id VARCHAR(128) NULL AFTER id");
            } catch (SQLException ignored) {
                // Existing installations already have the column.
            }
            try {
                statement.executeUpdate("CREATE UNIQUE INDEX uniq_external_id ON " + purchaseLogsTable + " (external_id)");
            } catch (SQLException ignored) {
                // Existing installations already have the index.
            }
        }
    }

    private static String dedupeKey(PurchaseInput input) {
        String raw = String.join("|", normalize(input.playerName()), normalize(input.serviceKey()), normalize(input.amount()), normalizePrice(input.price()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizePrice(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
