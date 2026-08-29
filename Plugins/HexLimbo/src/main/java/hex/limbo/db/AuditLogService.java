package hex.limbo.db;

import org.slf4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Inserts entries into {@code hex_limbo_audit_log} asynchronously so the proxy thread is never
 * blocked on writes. No-ops when no datasource is available.
 */
public final class AuditLogService implements hex.limbo.auth.AuthFlow.AuditLog {

    private final DataSource dataSource;
    private final Executor executor;
    private final Logger logger;

    public AuditLogService(DataSource dataSource, Executor executor, Logger logger) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.logger = logger;
    }

    public boolean isEnabled() {
        return dataSource != null && executor != null;
    }

    @Override
    public void record(String action, String usernameLower, UUID uuid, String ipHash, String detail) {
        if (!isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        executor.execute(() -> {
            String sql = """
                    INSERT INTO hex_limbo_audit_log
                        (username_lower, uuid, action, ip_hash, detail, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """;
            try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, usernameLower);
                ps.setString(2, uuid == null ? null : uuid.toString());
                ps.setString(3, action);
                ps.setString(4, ipHash);
                ps.setString(5, detail);
                ps.setLong(6, now);
                ps.executeUpdate();
            } catch (SQLException ex) {
                logger.warn("Could not write audit log entry action={} user={}", action, usernameLower, ex);
            }
        });
    }
}
