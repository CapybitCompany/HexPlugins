package hexabovename.config;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record HexAboveNameConfig(
        Render render,
        Worlds worlds,
        Storage storage,
        Messages messages
) {
    public HexAboveNameConfig {
        render = Objects.requireNonNull(render, "render");
        worlds = Objects.requireNonNull(worlds, "worlds");
        storage = Objects.requireNonNull(storage, "storage");
        messages = Objects.requireNonNull(messages, "messages");
    }

    public boolean isWorldAllowed(String worldName) {
        if (!worlds.useWhitelist()) {
            return true;
        }
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        return worlds.whitelist().contains(worldName.toLowerCase(Locale.ROOT));
    }

    public record Render(
            long updateIntervalTicks,
            double yOffset,
            boolean showToSelf
    ) {
        public Render {
            updateIntervalTicks = Math.max(1L, updateIntervalTicks);
            yOffset = Math.max(0.0D, yOffset);
        }
    }

    public record Worlds(
            boolean useWhitelist,
            Set<String> whitelist
    ) {
        public Worlds {
            whitelist = normalizeWhitelist(whitelist);
        }

        private static Set<String> normalizeWhitelist(Set<String> values) {
            if (values == null || values.isEmpty()) {
                return Set.of();
            }
            Set<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                normalized.add(value.toLowerCase(Locale.ROOT));
            }
            return Set.copyOf(normalized);
        }
    }

    public record Storage(
            StorageType type,
            long refreshIntervalTicks,
            String yamlFile,
            MySql mysql
    ) {
        public Storage {
            type = Objects.requireNonNull(type, "type");
            refreshIntervalTicks = Math.max(20L, refreshIntervalTicks);
            yamlFile = (yamlFile == null || yamlFile.isBlank()) ? "users.yml" : yamlFile;
            mysql = Objects.requireNonNull(mysql, "mysql");
        }
    }

    public record MySql(
            String host,
            int port,
            String database,
            String username,
            String password,
            String table,
            Columns columns,
            Options options
    ) {
        public MySql {
            host = fallback(host, "127.0.0.1");
            port = Math.max(1, port);
            database = fallback(database, "minecraft_network");
            username = fallback(username, "root");
            password = password == null ? "" : password;
            table = sanitizeIdentifier(table, "player_display_texts");
            columns = Objects.requireNonNull(columns, "columns");
            options = Objects.requireNonNull(options, "options");
        }
    }

    public record Columns(
            String player,
            String uuid,
            String text
    ) {
        public Columns {
            player = sanitizeIdentifier(player, "player");
            uuid = sanitizeIdentifier(uuid, "uuid");
            text = sanitizeIdentifier(text, "string");
        }
    }

    public record Options(
            boolean useSsl,
            String serverTimezone
    ) {
        public Options {
            serverTimezone = fallback(serverTimezone, "UTC");
        }
    }

    public record Messages(
            String prefix,
            String reloaded,
            String reloadFailed,
            String noPermission,
            String usage
    ) {
        public Messages {
            prefix = Objects.requireNonNull(prefix, "prefix");
            reloaded = Objects.requireNonNull(reloaded, "reloaded");
            reloadFailed = Objects.requireNonNull(reloadFailed, "reloadFailed");
            noPermission = Objects.requireNonNull(noPermission, "noPermission");
            usage = Objects.requireNonNull(usage, "usage");
        }
    }

    private static String fallback(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static String sanitizeIdentifier(String identifier, String fallback) {
        String candidate = fallback(identifier, fallback);
        if (!candidate.matches("[A-Za-z0-9_]+")) {
            return fallback;
        }
        return candidate;
    }
}
