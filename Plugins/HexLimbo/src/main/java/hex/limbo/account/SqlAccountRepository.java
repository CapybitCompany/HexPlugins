package hex.limbo.account;

import hex.limbo.db.MySqlProvider;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * MySQL/MariaDB-backed {@link AccountRepository}. Pool comes from {@link MySqlProvider}.
 */
public final class SqlAccountRepository implements AccountRepository {

    private static final String TABLE = "hex_limbo_accounts";

    private final DataSource dataSource;
    private final Logger logger;
    private final MySqlProvider provider;

    public SqlAccountRepository(MySqlProvider provider, Logger logger) {
        this.provider = provider;
        this.dataSource = provider.dataSource();
        this.logger = logger;
    }

    @Override
    public void initializeSchema() {
        String accounts = """
                CREATE TABLE IF NOT EXISTS hex_limbo_accounts (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    username_lower VARCHAR(16) NOT NULL UNIQUE,
                    last_username VARCHAR(16) NOT NULL,
                    account_type VARCHAR(16) NOT NULL,
                    uuid CHAR(36) NOT NULL UNIQUE,
                    premium_uuid CHAR(36) NULL,
                    password_hash VARCHAR(255) NULL,
                    registered_at BIGINT NOT NULL,
                    last_login_at BIGINT NULL,
                    last_ip_hash VARCHAR(128) NULL,
                    failed_attempts INT NOT NULL DEFAULT 0,
                    locked_until BIGINT NULL,
                    INDEX idx_last_ip_hash (last_ip_hash)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
        String sessions = """
                CREATE TABLE IF NOT EXISTS hex_limbo_sessions (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    account_id BIGINT NOT NULL,
                    uuid CHAR(36) NOT NULL,
                    username_lower VARCHAR(16) NOT NULL,
                    ip_hash VARCHAR(128) NOT NULL,
                    created_at BIGINT NOT NULL,
                    expires_at BIGINT NOT NULL,
                    INDEX idx_sessions_uuid (uuid),
                    INDEX idx_sessions_expires (expires_at),
                    CONSTRAINT fk_sessions_account FOREIGN KEY (account_id) REFERENCES hex_limbo_accounts(id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
        String audit = """
                CREATE TABLE IF NOT EXISTS hex_limbo_audit_log (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    username_lower VARCHAR(16) NULL,
                    uuid CHAR(36) NULL,
                    action VARCHAR(64) NOT NULL,
                    ip_hash VARCHAR(128) NULL,
                    detail VARCHAR(255) NULL,
                    created_at BIGINT NOT NULL,
                    INDEX idx_audit_username (username_lower),
                    INDEX idx_audit_action (action)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(accounts);
            st.execute(sessions);
            st.execute(audit);
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not initialize HexLimbo schema", ex);
        }
    }

    @Override
    public Optional<Account> findByUsername(String usernameLower) {
        String sql = "SELECT * FROM " + TABLE + " WHERE username_lower = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, usernameLower.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapAccount(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("findByUsername failed", ex);
        }
    }

    @Override
    public Optional<Account> findByUuid(UUID uuid) {
        String sql = "SELECT * FROM " + TABLE + " WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapAccount(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("findByUuid failed", ex);
        }
    }

    @Override
    public Account create(Account candidate) {
        String sql = """
                INSERT INTO hex_limbo_accounts
                    (username_lower, last_username, account_type, uuid, premium_uuid,
                     password_hash, registered_at, last_login_at, last_ip_hash,
                     failed_attempts, locked_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, candidate.usernameLower());
            ps.setString(2, candidate.lastUsername());
            ps.setString(3, candidate.accountType().name());
            ps.setString(4, candidate.uuid().toString());
            if (candidate.premiumUuid() != null) {
                ps.setString(5, candidate.premiumUuid().toString());
            } else {
                ps.setNull(5, java.sql.Types.CHAR);
            }
            ps.setString(6, candidate.passwordHash());
            ps.setLong(7, candidate.registeredAt());
            if (candidate.lastLoginAt() != null) {
                ps.setLong(8, candidate.lastLoginAt());
            } else {
                ps.setNull(8, java.sql.Types.BIGINT);
            }
            ps.setString(9, candidate.lastIpHash());
            ps.setInt(10, candidate.failedAttempts());
            if (candidate.lockedUntil() != null) {
                ps.setLong(11, candidate.lockedUntil());
            } else {
                ps.setNull(11, java.sql.Types.BIGINT);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    candidate.setId(keys.getLong(1));
                }
            }
            return candidate;
        } catch (SQLException ex) {
            throw new IllegalStateException("create failed", ex);
        }
    }

    @Override
    public void updatePasswordHash(long id, String passwordHash) {
        execute("UPDATE " + TABLE + " SET password_hash = ?, failed_attempts = 0, locked_until = NULL WHERE id = ?",
                ps -> { ps.setString(1, passwordHash); ps.setLong(2, id); });
    }

    @Override
    public void updateFailedAttempts(long id, int failedAttempts, Long lockedUntilMillis) {
        execute("UPDATE " + TABLE + " SET failed_attempts = ?, locked_until = ? WHERE id = ?",
                ps -> {
                    ps.setInt(1, failedAttempts);
                    if (lockedUntilMillis != null) {
                        ps.setLong(2, lockedUntilMillis);
                    } else {
                        ps.setNull(2, java.sql.Types.BIGINT);
                    }
                    ps.setLong(3, id);
                });
    }

    @Override
    public void recordSuccessfulLogin(long id, long nowMillis, String ipHash, String lastUsername) {
        execute("""
                UPDATE hex_limbo_accounts
                SET last_login_at = ?, last_ip_hash = ?, last_username = ?,
                    failed_attempts = 0, locked_until = NULL
                WHERE id = ?
                """,
                ps -> {
                    ps.setLong(1, nowMillis);
                    ps.setString(2, ipHash);
                    ps.setString(3, lastUsername);
                    ps.setLong(4, id);
                });
    }

    @Override
    public void updateAccountType(long id, AccountType type) {
        execute("UPDATE " + TABLE + " SET account_type = ? WHERE id = ?",
                ps -> { ps.setString(1, type.name()); ps.setLong(2, id); });
    }

    @Override
    public void updatePremiumUuid(long id, UUID premiumUuid) {
        execute("UPDATE " + TABLE + " SET premium_uuid = ? WHERE id = ?",
                ps -> {
                    if (premiumUuid != null) {
                        ps.setString(1, premiumUuid.toString());
                    } else {
                        ps.setNull(1, java.sql.Types.CHAR);
                    }
                    ps.setLong(2, id);
                });
    }

    @Override
    public void updateUuid(long id, UUID uuid) {
        execute("UPDATE " + TABLE + " SET uuid = ? WHERE id = ?",
                ps -> { ps.setString(1, uuid.toString()); ps.setLong(2, id); });
    }

    @Override
    public boolean promotePendingMigrationToPremium(
            long id,
            UUID realUuid,
            long nowMillis,
            String ipHash,
            String lastUsername
    ) {
        // Single UPDATE so the migration is row-atomic. We inspect the row count returned by
        // executeUpdate() to distinguish a successful promotion from a race-lost no-op.
        String sql = """
                UPDATE hex_limbo_accounts
                SET uuid = ?,
                    premium_uuid = ?,
                    account_type = 'PREMIUM',
                    last_login_at = ?,
                    last_ip_hash = ?,
                    last_username = ?,
                    failed_attempts = 0,
                    locked_until = NULL
                WHERE id = ? AND account_type = 'PENDING_MIGRATION'
                """;
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, realUuid.toString());
            ps.setString(2, realUuid.toString());
            ps.setLong(3, nowMillis);
            ps.setString(4, ipHash);
            ps.setString(5, lastUsername);
            ps.setLong(6, id);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                return false;
            }
            if (updated > 1) {
                // Should be unreachable: id is the primary key. If MySQL claims otherwise, refuse
                // to authenticate against an ambiguous identity.
                throw new IllegalStateException("promotePendingMigrationToPremium matched " + updated
                        + " rows for id=" + id + " – data corruption suspected.");
            }
            return true;
        } catch (SQLException ex) {
            throw new IllegalStateException("promotePendingMigrationToPremium failed for id=" + id, ex);
        }
    }

    @Override
    public int countByIp(String ipHash) {
        String sql = "SELECT COUNT(*) FROM " + TABLE + " WHERE last_ip_hash = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ipHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("countByIp failed", ex);
        }
    }

    @Override
    public void delete(long id) {
        execute("DELETE FROM " + TABLE + " WHERE id = ?", ps -> ps.setLong(1, id));
    }

    @Override
    public void close() {
        // Pool ownership lives on MySqlProvider; nothing extra here.
    }

    private void execute(String sql, SqlBinder binder) {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("SQL failed: " + sql, ex);
        }
    }

    private Account mapAccount(ResultSet rs) throws SQLException {
        long lastLoginRaw = rs.getLong("last_login_at");
        Long lastLoginAt = rs.wasNull() ? null : lastLoginRaw;
        long lockedRaw = rs.getLong("locked_until");
        Long lockedUntil = rs.wasNull() ? null : lockedRaw;
        String premiumUuidStr = rs.getString("premium_uuid");
        UUID premiumUuid = premiumUuidStr == null ? null : UUID.fromString(premiumUuidStr);
        return new Account(
                rs.getLong("id"),
                rs.getString("username_lower"),
                rs.getString("last_username"),
                AccountType.valueOf(rs.getString("account_type")),
                UUID.fromString(rs.getString("uuid")),
                premiumUuid,
                rs.getString("password_hash"),
                rs.getLong("registered_at"),
                lastLoginAt,
                rs.getString("last_ip_hash"),
                rs.getInt("failed_attempts"),
                lockedUntil
        );
    }

    public MySqlProvider provider() {
        return provider;
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
