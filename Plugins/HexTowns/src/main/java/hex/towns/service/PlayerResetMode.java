package hex.towns.service;

public enum PlayerResetMode {
    FULL,
    TOWN_BOUND_ONLY,
    NONE;

    public static PlayerResetMode parse(String raw, PlayerResetMode fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
