package hex.limbo.testsupport;

import hex.limbo.config.ForwardingMode;
import hex.limbo.config.PluginConfig;

import java.util.List;

/**
 * Shared factory for {@link PluginConfig} instances used in tests. Centralises the (long) record
 * constructors so individual tests don't break every time we add a new field.
 */
public final class TestConfigs {

    private TestConfigs() {}

    public static PluginConfig.Limbo defaultLimbo() {
        return new PluginConfig.Limbo(
                "hexlimbo-limbo",
                "127.0.0.1",
                25580,
                0.5, 64.0, 0.5,
                0.0f, 0.0f,
                false,
                "Please login or register.",
                new PluginConfig.Forwarding(ForwardingMode.NONE, ""),
                false
        );
    }

    public static PluginConfig.Limbo limboWithForwarding(ForwardingMode mode, String secret) {
        PluginConfig.Limbo base = defaultLimbo();
        return new PluginConfig.Limbo(
                base.serverName(), base.bindHost(), base.bindPort(),
                base.spawnX(), base.spawnY(), base.spawnZ(), base.spawnYaw(), base.spawnPitch(),
                base.actionbarEnabled(), base.actionbarText(),
                new PluginConfig.Forwarding(mode, secret),
                base.debugProtocol()
        );
    }

    public static PluginConfig.Prompts defaultPrompts() {
        return new PluginConfig.Prompts(
                true, true, true, true,
                15L,
                "YELLOW", "PROGRESS", 1.0f,
                true, true, true
        );
    }

    /** Default prompts with the premium and admin-bypass greetings toggled independently. */
    public static PluginConfig.Prompts promptsWithSkipGates(boolean premium, boolean adminBypass) {
        PluginConfig.Prompts base = defaultPrompts();
        return new PluginConfig.Prompts(
                base.enabled(), base.bossbarEnabled(), base.titleEnabled(), base.chatEnabled(),
                base.reminderIntervalSeconds(),
                base.bossbarColor(), base.bossbarOverlay(), base.bossbarProgress(),
                base.successTitleEnabled(), premium, adminBypass
        );
    }

    /** Replaces only the prompts block of the default config. */
    public static PluginConfig withPrompts(PluginConfig.Prompts prompts) {
        PluginConfig base = defaultConfig();
        return new PluginConfig(
                base.targetServer(),
                base.loginTimeoutSeconds(),
                base.adminBypassPermission(),
                base.allowedCommandsUnauthenticated().stream().toList(),
                base.database(),
                base.session(),
                base.security(),
                base.premium(),
                base.limbo(),
                prompts
        );
    }

    public static PluginConfig defaultConfig() {
        return new PluginConfig(
                "lobby",
                60L,
                "hexlimbo.bypass",
                List.of("login", "register", "limbo"),
                new PluginConfig.Database("127.0.0.1", 3306, "db", "user", "pass", 10, 10_000L, 10_000L, false, true, true),
                new PluginConfig.Session(true, 240L, 10L),
                new PluginConfig.Security(8, 3, 600L, 10, 4, "pepper"),
                new PluginConfig.Premium(true, 600L, 10_000, 4_000L, false),
                defaultLimbo(),
                defaultPrompts()
        );
    }

    public static PluginConfig withMinPasswordLength(int minLength) {
        PluginConfig base = defaultConfig();
        return new PluginConfig(
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
                base.premium(),
                base.limbo(),
                base.prompts()
        );
    }

    public static PluginConfig withAllowlist(List<String> allowed) {
        PluginConfig base = defaultConfig();
        return new PluginConfig(
                base.targetServer(),
                base.loginTimeoutSeconds(),
                base.adminBypassPermission(),
                allowed,
                base.database(),
                base.session(),
                base.security(),
                base.premium(),
                base.limbo(),
                base.prompts()
        );
    }

    public static PluginConfig withServers(String limboServerName, String targetServer) {
        PluginConfig base = defaultConfig();
        PluginConfig.Limbo l = base.limbo();
        PluginConfig.Limbo overriddenLimbo = new PluginConfig.Limbo(
                limboServerName, l.bindHost(), l.bindPort(),
                l.spawnX(), l.spawnY(), l.spawnZ(), l.spawnYaw(), l.spawnPitch(),
                l.actionbarEnabled(), l.actionbarText(),
                l.forwarding(),
                l.debugProtocol()
        );
        return new PluginConfig(
                targetServer,
                base.loginTimeoutSeconds(),
                base.adminBypassPermission(),
                base.allowedCommandsUnauthenticated().stream().toList(),
                base.database(),
                base.session(),
                base.security(),
                base.premium(),
                overriddenLimbo,
                base.prompts()
        );
    }

    public static PluginConfig withPremiumFailOpen(boolean failOpen) {
        PluginConfig base = defaultConfig();
        return new PluginConfig(
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
                ),
                base.limbo(),
                base.prompts()
        );
    }

    public static PluginConfig withLimboPort(int port) {
        PluginConfig base = defaultConfig();
        PluginConfig.Limbo l = base.limbo();
        PluginConfig.Limbo overridden = new PluginConfig.Limbo(
                l.serverName(), l.bindHost(), port,
                l.spawnX(), l.spawnY(), l.spawnZ(), l.spawnYaw(), l.spawnPitch(),
                l.actionbarEnabled(), l.actionbarText(),
                l.forwarding(),
                l.debugProtocol()
        );
        return new PluginConfig(
                base.targetServer(),
                base.loginTimeoutSeconds(),
                base.adminBypassPermission(),
                base.allowedCommandsUnauthenticated().stream().toList(),
                base.database(),
                base.session(),
                base.security(),
                base.premium(),
                overridden,
                base.prompts()
        );
    }
}
