package hexposterunki.engine;

import java.util.Locale;

/**
 * Why a player may or may not act on the encounter right now.
 *
 * <p>Pulled out of the listener so damage handling and kill crediting share one answer instead of
 * each applying their own partial check.
 */
public enum Participation {

    /** Full participant: inside the region, alive, online and in the controlling town. */
    ELIGIBLE(null),

    /** No live encounter, or the phase carries no fighting. */
    NO_ACTIVE_RUN("brak aktywnego posterunku"),

    /** Standing outside the event region - shooting in from the outside does not count. */
    OUTSIDE_REGION("poza terenem posterunku"),

    /** Dead, offline or spectating. */
    NOT_ALIVE("gracz nieaktywny"),

    /** No town at all. */
    NO_TOWN("brak drużyny"),

    /** In a town, but not the one holding the outpost. */
    WRONG_TOWN("inna drużyna kontroluje posterunek"),

    /**
     * The encounter exists but is frozen: persistence paused, the engine stopped or a reset running.
     * Nobody - not even an administrator with bypass - may deal encounter damage or earn a kill,
     * because nothing that happens now could be stored.
     */
    SUSPENDED("wydarzenie wstrzymane"),

    /**
     * Allowed to act through {@code hexposterunki.admin.bypass}. Damage goes through, but this
     * deliberately does not create ranking positions or reward claims.
     */
    ADMIN_BYPASS(null);

    private final String polishReason;

    Participation(String polishReason) {
        this.polishReason = polishReason;
    }

    public String polishReason() {
        return polishReason == null ? "" : polishReason;
    }

    /** May damage outpost mobs and the boss. */
    public boolean mayDamage() {
        return this == ELIGIBLE || this == ADMIN_BYPASS;
    }

    /** May be credited with a kill and gather ranking progress. */
    public boolean mayEarnKill() {
        return this == ELIGIBLE;
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
