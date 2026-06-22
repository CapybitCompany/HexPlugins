package hex.limbo.auth;

import hex.limbo.config.RuntimeContext;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent session store. A session is a (uuid, ip-hash, expiry) triple. When a player joins,
 * if a valid session is found for their (uuid, ip-hash), they are auto-logged in. Sessions are
 * invalidated on logout, password change, and explicit admin action.
 *
 * <p>The {@code session.enabled} flag and {@code session.duration-minutes} are read fresh from
 * {@link RuntimeContext} on every operation so {@code /hexlimbo reload} immediately changes
 * behaviour: turning sessions off short-circuits new lookups/inserts, and increasing the duration
 * lengthens sessions created from that point onward.
 *
 * <p>All operations no-op when the datasource is {@code null} (DB unavailable) so a misconfigured
 * proxy never NPEs through this service.
 */
public final class SessionService {

    private static final long MIN_DURATION_MILLIS = 60_000L;

    private final DataSource dataSource;
    private final RuntimeContext context;
    private final Logger logger;

    public SessionService(DataSource dataSource, RuntimeContext context, Logger logger) {
        this.dataSource = dataSource;
        this.context = context;
        this.logger = logger;
    }

    public boolean isEnabled() {
        return dataSource != null && context.config().session().enabled();
    }

    /** Package-private for unit tests that verify reload-driven duration changes. */
    long currentDurationMillis() {
        long minutes = context.config().session().durationMinutes();
        return Math.max(MIN_DURATION_MILLIS, minutes * 60_000L);
    }

    public void createSession(long accountId, UUID uuid, String usernameLower, String ipHash) {
        if (!isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long expires = now + currentDurationMillis();
        String sql = """
                INSERT INTO hex_limbo_sessions
                    (account_id, uuid, username_lower, ip_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            ps.setString(2, uuid.toString());
            ps.setString(3, usernameLower);
            ps.setString(4, ipHash);
            ps.setLong(5, now);
            ps.setLong(6, expires);
            ps.executeUpdate();
        } catch (SQLException ex) {
            logger.warn("Could not persist session for {}: {}", usernameLower, ex.getMessage());
        }
    }

    public Optional<Long> findValidSessionExpiry(UUID uuid, String ipHash) {
        if (!isEnabled() || ipHash == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        String sql = """
                SELECT expires_at FROM hex_limbo_sessions
                WHERE uuid = ? AND ip_hash = ? AND expires_at > ?
                ORDER BY expires_at DESC LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, ipHash);
            ps.setLong(3, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong(1));
                }
            }
        } catch (SQLException ex) {
            logger.warn("Session lookup failed: {}", ex.getMessage());
        }
        return Optional.empty();
    }

    public Optional<Long> findLatestExpiryForUuid(UUID uuid) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        String sql = """
                SELECT expires_at FROM hex_limbo_sessions
                WHERE uuid = ? AND expires_at > ?
                ORDER BY expires_at DESC LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong(1));
                }
            }
        } catch (SQLException ex) {
            logger.warn("Session admin lookup failed: {}", ex.getMessage());
        }
        return Optional.empty();
    }

    public int countValidSessionsForUuid(UUID uuid) {
        if (!isEnabled()) {
            return 0;
        }
        String sql = "SELECT COUNT(*) FROM hex_limbo_sessions WHERE uuid = ? AND expires_at > ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            logger.warn("Session count failed: {}", ex.getMessage());
            return 0;
        }
    }

    public void invalidate(UUID uuid) {
        if (dataSource == null) {
            return;
        }
        String sql = "DELETE FROM hex_limbo_sessions WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            logger.warn("Session invalidate failed for {}: {}", uuid, ex.getMessage());
        }
    }

    public int purgeExpired() {
        if (dataSource == null) {
            return 0;
        }
        String sql = "DELETE FROM hex_limbo_sessions WHERE expires_at <= ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            return ps.executeUpdate();
        } catch (SQLException ex) {
            logger.warn("Session purge failed: {}", ex.getMessage());
            return 0;
        }
    }
}
