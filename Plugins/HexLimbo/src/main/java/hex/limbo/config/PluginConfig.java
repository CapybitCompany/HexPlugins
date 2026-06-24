package hex.limbo.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable plugin configuration loaded from {@code config.yml}.
 */
public final class PluginConfig {

    public record Database(
            String host,
            int port,
            String database,
            String username,
            String password,
            int poolSize,
            long connectionTimeoutMs,
            long socketTimeoutMs,
            boolean useSsl,
            boolean allowPublicKeyRetrieval,
            boolean failFast
    ) {}

    public record Session(
            boolean enabled,
            long durationMinutes,
            long purgeIntervalMinutes
    ) {}

    public record Security(
            int minPasswordLength,
            int maxFailedAttempts,
            long lockoutSeconds,
            int rateLimitPerMinute,
            int maxAccountsPerIp,
            String ipHashPepper
    ) {}

    public record Premium(
            boolean enabled,
            long cacheTtlSeconds,
            int cacheMaxEntries,
            long httpTimeoutMs,
            boolean failOpenOnCheckError
    ) {}

    private final String limboServer;
    private final String targetServer;
    private final long loginTimeoutSeconds;
    private final String adminBypassPermission;
    private final Set<String> allowedCommandsUnauthenticated;
    private final Database database;
    private final Session session;
    private final Security security;
    private final Premium premium;

    public PluginConfig(
            String limboServer,
            String targetServer,
            long loginTimeoutSeconds,
            String adminBypassPermission,
            List<String> allowedCommandsUnauthenticated,
            Database database,
            Session session,
            Security security,
            Premium premium
    ) {
        this.limboServer = Objects.requireNonNull(limboServer, "limboServer");
        this.targetServer = Objects.requireNonNull(targetServer, "targetServer");
        this.loginTimeoutSeconds = loginTimeoutSeconds;
        this.adminBypassPermission = Objects.requireNonNull(adminBypassPermission, "adminBypassPermission");
        this.allowedCommandsUnauthenticated = Set.copyOf(allowedCommandsUnauthenticated);
        this.database = Objects.requireNonNull(database, "database");
        this.session = Objects.requireNonNull(session, "session");
        this.security = Objects.requireNonNull(security, "security");
        this.premium = Objects.requireNonNull(premium, "premium");
    }

    public String limboServer() { return limboServer; }
    public String targetServer() { return targetServer; }
    public long loginTimeoutSeconds() { return loginTimeoutSeconds; }
    public String adminBypassPermission() { return adminBypassPermission; }
    public Set<String> allowedCommandsUnauthenticated() { return allowedCommandsUnauthenticated; }
    public Database database() { return database; }
    public Session session() { return session; }
    public Security security() { return security; }
    public Premium premium() { return premium; }
}
