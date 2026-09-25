package hexposterunki.domain;

import java.util.Locale;

/** Lifecycle of the boss that belongs to a single run. */
public enum BossStatus {

    /** The roll decided that this run has no boss at all. */
    NONE("brak"),

    /** A boss id was rolled but the entity has not been created yet. */
    PENDING("oczekuje"),

    /** The boss entity exists in the world and is bound to this run. */
    ALIVE("żywy"),

    /** The boss of this run has been confirmed dead through a death event. */
    DEAD("pokonany"),

    /**
     * The boss entity cannot be found any more without a death event - despawned, removed by
     * another plugin, or its chunk went away. Explicitly not a victory.
     */
    MISSING("zaginiony");

    private final String polishLabel;

    BossStatus(String polishLabel) {
        this.polishLabel = polishLabel;
    }

    public String polishLabel() {
        return polishLabel;
    }

    public static BossStatus parse(String raw, BossStatus fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return BossStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    /** True while recovery should try to bind or recreate the boss entity. */
    public boolean wantsEntity() {
        return this == PENDING || this == ALIVE || this == MISSING;
    }
}
