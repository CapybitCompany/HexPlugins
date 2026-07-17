package pl.hexnetwork.hexnametags.persistence;

import org.bukkit.configuration.ConfigurationSection;

public record NameTagPersistenceSettings(
        boolean enabled,
        boolean createTable,
        boolean savePlayerTags,
        boolean loadOnlineOnStart,
        String tableName,
        long cacheTtlMillis
) {
    public static NameTagPersistenceSettings fromConfig(ConfigurationSection section) {
        if (section == null) {
            return defaults();
        }

        String tableName = section.getString("table-name", "hex_nametags");
        if (tableName == null || tableName.isBlank()) {
            tableName = "hex_nametags";
        }

        double seconds = section.getDouble("cache-ttl-seconds", 10.0D);
        long ttlMillis = Math.max(0L, Math.round(seconds * 1000.0D));

        return new NameTagPersistenceSettings(
                section.getBoolean("enabled", true),
                section.getBoolean("create-table", true),
                section.getBoolean("save-player-tags", true),
                section.getBoolean("load-online-on-start", true),
                sanitizeTableName(tableName),
                ttlMillis
        );
    }

    public static NameTagPersistenceSettings defaults() {
        return new NameTagPersistenceSettings(true, true, true, true, "hex_nametags", 10_000L);
    }

    private static String sanitizeTableName(String input) {
        String value = input.trim();
        if (!value.matches("[A-Za-z0-9_]+")) {
            return "hex_nametags";
        }
        return value;
    }
}
