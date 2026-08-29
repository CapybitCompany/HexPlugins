package hex.limbo.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code config.yml} and {@code messages.yml} from the plugin data directory, writing defaults
 * from bundled resources when they are absent. Uses SnakeYAML and tolerates missing keys.
 *
 * <p>Before either file is parsed it is handed to {@link ConfigMigrator}, which upgrades a file
 * written by an older release in place: new keys are added, values still matching the old defaults
 * are refreshed, and deliberate customisations are kept. The migration is versioned and idempotent,
 * so a file that is already current is read straight through.
 */
public final class ConfigLoader {

    private static final String CONFIG_FILE = "config.yml";
    private static final String MESSAGES_FILE = "messages.yml";

    private final Path dataDirectory;
    private final Logger logger;
    private final ConfigMigrator migrator;

    public ConfigLoader(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.migrator = new ConfigMigrator(logger);
    }

    public PluginConfig loadConfig() throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve(CONFIG_FILE);
        if (Files.notExists(file)) {
            writeDefaultResource(file, CONFIG_FILE);
        } else {
            migrator.migrateConfig(file);
        }

        Map<String, Object> root = readYaml(file);
        Map<String, Object> auth = section(root, "auth");
        Map<String, Object> servers = section(root, "servers");
        Map<String, Object> dbSection = section(root, "database");
        Map<String, Object> sessionSection = section(root, "session");
        Map<String, Object> securitySection = section(root, "security");
        Map<String, Object> premiumSection = section(root, "premium");
        Map<String, Object> promptsSection = section(root, "prompts");
        Map<String, Object> limboSection = section(root, "limbo");
        Map<String, Object> limboSpawn = section(limboSection, "spawn");
        Map<String, Object> limboForwarding = section(limboSection, "forwarding");

        String targetServer = string(servers, "target", "lobby");
        long loginTimeoutSeconds = number(auth, "login-timeout-seconds", 60L).longValue();
        String adminBypass = string(auth, "admin-bypass-permission", "hexlimbo.bypass");
        List<String> allowed = stringList(auth, "allowed-commands-unauthenticated",
                List.of("login", "register", "l", "reg", "limbo", "premium", "cpw", "changepassword", "logout"));

        PluginConfig.Database database = new PluginConfig.Database(
                string(dbSection, "host", "127.0.0.1"),
                number(dbSection, "port", 3306).intValue(),
                string(dbSection, "database", "hexlimbo"),
                string(dbSection, "username", "hexlimbo"),
                string(dbSection, "password", ""),
                number(dbSection, "pool-size", 10).intValue(),
                number(dbSection, "connection-timeout-ms", 10_000L).longValue(),
                number(dbSection, "socket-timeout-ms", 10_000L).longValue(),
                bool(dbSection, "use-ssl", false),
                bool(dbSection, "allow-public-key-retrieval", true),
                bool(dbSection, "fail-fast", true)
        );

        PluginConfig.Session session = new PluginConfig.Session(
                bool(sessionSection, "enabled", true),
                number(sessionSection, "duration-minutes", 240L).longValue(),
                number(sessionSection, "purge-interval-minutes", 10L).longValue()
        );

        PluginConfig.Security security = new PluginConfig.Security(
                number(securitySection, "min-password-length", 8).intValue(),
                number(securitySection, "max-failed-attempts", 5).intValue(),
                number(securitySection, "lockout-seconds", 600L).longValue(),
                number(securitySection, "rate-limit-per-minute", 6).intValue(),
                number(securitySection, "max-accounts-per-ip", 4).intValue(),
                string(securitySection, "ip-hash-pepper", "change-me-please-set-a-long-random-value")
        );

        PluginConfig.Premium premium = new PluginConfig.Premium(
                bool(premiumSection, "enabled", true),
                number(premiumSection, "cache-ttl-seconds", 600L).longValue(),
                number(premiumSection, "cache-max-entries", 10_000).intValue(),
                number(premiumSection, "http-timeout-ms", 4_000L).longValue(),
                bool(premiumSection, "fail-open-on-check-error", false)
        );

        // Legacy alias: before config-version 2 a single 'premium-skip-enabled' gated both the
        // premium and the admin-bypass greeting. A hand-written or un-migrated old file must keep
        // behaving the same, so it seeds both flags whenever the newer keys are absent.
        boolean legacySkipEnabled = bool(promptsSection, "premium-skip-enabled", true);

        PluginConfig.Prompts prompts = new PluginConfig.Prompts(
                bool(promptsSection, "enabled", true),
                bool(promptsSection, "bossbar-enabled", true),
                bool(promptsSection, "title-enabled", true),
                bool(promptsSection, "chat-enabled", true),
                number(promptsSection, "reminder-interval-seconds", 15L).longValue(),
                string(promptsSection, "bossbar-color", "YELLOW"),
                string(promptsSection, "bossbar-overlay", "PROGRESS"),
                number(promptsSection, "bossbar-progress", 1.0).floatValue(),
                bool(promptsSection, "success-title-enabled", true),
                bool(promptsSection, "premium-success-enabled", legacySkipEnabled),
                bool(promptsSection, "admin-bypass-success-enabled", legacySkipEnabled)
        );

        PluginConfig.Forwarding forwarding = new PluginConfig.Forwarding(
                ForwardingMode.parse(string(limboForwarding, "mode", "MODERN"), ForwardingMode.MODERN),
                string(limboForwarding, "secret", "")
        );

        PluginConfig.Limbo limbo = new PluginConfig.Limbo(
                string(limboSection, "server-name", "hexlimbo-limbo"),
                string(limboSection, "bind-host", "127.0.0.1"),
                number(limboSection, "bind-port", 25580).intValue(),
                number(limboSpawn, "x", 0.5).doubleValue(),
                number(limboSpawn, "y", 64.0).doubleValue(),
                number(limboSpawn, "z", 0.5).doubleValue(),
                number(limboSpawn, "yaw", 0.0).floatValue(),
                number(limboSpawn, "pitch", 0.0).floatValue(),
                // Default off: the v1 NBT text-component encoding has not been verified against
                // every 1.21.11 client build and a malformed packet here disconnects the player.
                bool(limboSection, "actionbar-enabled", false),
                string(limboSection, "actionbar-text", "Zaloguj się przez /login lub zarejestruj przez /register."),
                forwarding,
                bool(limboSection, "debug-protocol", false)
        );

        if (servers.containsKey("limbo")) {
            logger.warn("config.yml: 'servers.limbo' is deprecated; HexLimbo manages its own internal limbo server. "
                    + "Configure 'limbo.server-name' instead.");
        }

        return new PluginConfig(
                targetServer,
                loginTimeoutSeconds,
                adminBypass,
                allowed,
                database,
                session,
                security,
                premium,
                limbo,
                prompts
        );
    }

    /**
     * Loads {@code messages.yml}, laying the operator's file over the bundled defaults.
     *
     * <p>The overlay is what {@code config.yml} has always had through its per-key fallbacks, and
     * messages need it for the same reason: a release that adds a key must not print the bare key
     * name at an operator whose file predates it. A value the operator set always wins - the
     * defaults only fill gaps - and a key they deleted comes back as its default rather than as the
     * literal string {@code admin.forcelogout.overtaken}.
     *
     * <p>{@link ConfigMigrator} still rewrites the file so new keys and their comments become
     * visible and editable; this is the runtime safety net underneath it, so a new message works
     * from the first startup whether or not the file has been migrated yet.
     */
    public MessagesConfig loadMessages() throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve(MESSAGES_FILE);
        if (Files.notExists(file)) {
            writeDefaultResource(file, MESSAGES_FILE);
        } else {
            migrator.migrateMessages(file);
        }
        Map<String, String> flat = new LinkedHashMap<>(bundledMessages());
        flat.putAll(messageEntries(readYaml(file)));
        return new MessagesConfig(flat);
    }

    /** The messages shipped inside the jar, used to fill gaps in an older operator file. */
    private Map<String, String> bundledMessages() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MESSAGES_FILE)) {
            if (in == null) {
                logger.warn("Bundled '{}' is missing from the plugin jar; messages absent from the "
                        + "operator file will render as their key.", MESSAGES_FILE);
                return Map.of();
            }
            try (Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                Object parsed = new Yaml().load(reader);
                return parsed instanceof Map<?, ?> map ? messageEntries(toStringKeyed(map)) : Map.of();
            }
        } catch (IOException | RuntimeException ex) {
            logger.warn("Could not read the bundled '{}': {}", MESSAGES_FILE, ex.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> messageEntries(Map<String, Object> raw) {
        Map<String, String> flat = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            // Bookkeeping, not a message: never let it be looked up and rendered to a player.
            if (ConfigMigrator.MESSAGES_VERSION_KEY.equals(entry.getKey())) {
                continue;
            }
            flat.put(entry.getKey(), String.valueOf(value));
        }
        return flat;
    }

    private static Map<String, Object> toStringKeyed(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null) {
                result.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return result;
    }

    private Map<String, Object> readYaml(Path file) throws IOException {
        Yaml yaml = new Yaml();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object parsed = yaml.load(reader);
            if (parsed == null) {
                return Collections.emptyMap();
            }
            if (parsed instanceof Map<?, ?> m) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() != null) {
                        result.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                return result;
            }
            return Collections.emptyMap();
        }
    }

    private void writeDefaultResource(Path target, String resourceName) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                logger.warn("Bundled default resource '{}' missing; writing empty file.", resourceName);
                Files.createFile(target);
                return;
            }
            Files.copy(in, target);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> root, String name) {
        Object raw = root.get(name);
        if (raw instanceof Map<?, ?>) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                if (e.getKey() != null) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            return out;
        }
        return Collections.emptyMap();
    }

    private String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? fallback : s;
    }

    private Number number(Map<String, Object> map, String key, Number fallback) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n;
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                logger.warn("Cannot parse number for key '{}' value '{}', using fallback {}", key, value, fallback);
            }
        }
        return fallback;
    }

    private boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value != null) {
            String s = String.valueOf(value).trim().toLowerCase();
            if ("true".equals(s)) return true;
            if ("false".equals(s)) return false;
        }
        return fallback;
    }

    private List<String> stringList(Map<String, Object> map, String key, List<String> fallback) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o).toLowerCase().trim());
                }
            }
            return List.copyOf(out);
        }
        return List.copyOf(fallback);
    }

    /** Used in tests to write defaults that don't depend on classpath resources. */
    public static void writeYaml(Path file, Map<String, Object> data) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            yaml.dump(data, writer);
        }
    }
}
