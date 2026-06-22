package hex.limbo.testsupport;

import hex.limbo.config.PluginConfig;

import java.util.List;

/**
 * Shared factory for {@link PluginConfig} instances used in tests. Centralises the (long) record
 * constructors so individual tests don't break every time we add a new field.
 */
public final class TestConfigs {

    private TestConfigs() {}

    public static PluginConfig defaultConfig() {
        return new PluginConfig(
                "limbo",
                "lobby",
                60L,
                "hexlimbo.bypass",
                List.of("login", "register", "limbo"),
                new PluginConfig.Database("127.0.0.1", 3306, "db", "user", "pass", 10, 10_000L, true),
                new PluginConfig.Session(true, 240L, 10L),
                new PluginConfig.Security(8, 3, 600L, 10, 4, "pepper"),
                new PluginConfig.Premium(true, 600L, 10_000, 4_000L, false)
        );
    }

    public static PluginConfig withMinPasswordLength(int minLength) {
        PluginConfig base = defaultConfig();
        return new PluginConfig(
                base.limboServer(),
                base.targetServer(),
                base.loginTimeoutSeconds(),
                base.adminBypassPermission(),
                base.allowedCommandsUnauthenticated().stream().toList(),
                base.database(),
                base.session(),
                new PluginConfig.Security(
                        minLength,
                        base.security().maxFailedAttempts(),
                        base.security().lockoutSeconds(),
                        base.security().rateLimitPerMinute(),
                        base.security().maxAccountsPerIp(),
                        base.security().ipHashPepper()
                ),
                base.premium()
        );
    }

    public static PluginConfig withAllowlist(List<String> allowed) {
        PluginConfig base = defaultConfig();
        return new PluginConfig(
                base.limboServer(),
                base.targetServer(),
                base.loginTimeoutSeconds(),
                base.adminBypassPermission(),
                allowed,
                base.database(),
                base.session(),
                base.security(),
                base.premium()
        );
    }

    public static PluginConfig withServers(String limboServer, String targetServer) {
        PluginConfig base = defaultConfig();
        return new PluginConfig(
                limboServer,
                targetServer,
                base.loginTimeoutSeconds(),
                base.adminBypassPermission(),
                base.allowedCommandsUnauthenticated().stream().toList(),
                base.database(),
                base.session(),
                base.security(),
                base.premium()
        );
    }

    public static PluginConfig withPremiumFailOpen(boolean failOpen) {
        PluginConfig base = defaultConfig();
        return new PluginConfig(
                base.limboServer(),
                base.targetServer(),
                base.loginTimeoutSeconds(),
                base.adminBypassPermission(),
                base.allowedCommandsUnauthenticated().stream().toList(),
                base.database(),
                base.session(),
                base.security(),
                new PluginConfig.Premium(
                        base.premium().enabled(),
                        base.premium().cacheTtlSeconds(),
                        base.premium().cacheMaxEntries(),
                        base.premium().httpTimeoutMs(),
                        failOpen
                )
        );
    }
}
