package hexabovename.config;

public enum StorageType {
    YAML,
    MYSQL;

    public static StorageType fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return YAML;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return YAML;
        }
    }
}
