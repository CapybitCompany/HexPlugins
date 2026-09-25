package hexposterunki.domain;

import java.util.Locale;

/** Explicit lifecycle phases of a single outpost encounter. */
public enum RunPhase {

    /** An outpost is announced and open, but no town has claimed it yet. */
    WAITING("Oczekiwanie na drużynę"),

    /** A town took control; the encounter is arming before the first wave. */
    PREPARATION("Przygotowanie"),

    /** Mob waves are running. */
    WAVES("Fale mobów"),

    /** The rolled boss is alive and must be defeated. */
    BOSS("Walka z bossem"),

    /** The encounter is won; ranking and reward claims are frozen. */
    COMPLETED("Zakończony"),

    /**
     * Winners may collect the loot. Containers and drops stay available, control no longer
     * changes, and the reset only happens once this phase elapses.
     */
    LOOTING("Zbieranie łupów"),

    /**
     * The fortress is being cleaned up after a finished, expired or stopped run. Persisted, so a
     * restart during the multi-tick cleanup finishes the cleanup instead of resuming the fight.
     * No combat progression, no control change, no victory and no new reward claims.
     */
    RESETTING("Resetowanie"),

    /** Nothing is active server-wide; the next location is picked once the timer elapses. */
    COOLDOWN("Przerwa"),

    /** Transient startup phase while persisted state is reconciled with the world. */
    RECOVERING("Przywracanie"),

    /**
     * A technical failure (spawn rejected, boss engine unusable, persistence down) stopped the
     * run. Never a victory: an administrator has to inspect and reset or reload.
     */
    FAILED("Błąd techniczny");

    private final String polishLabel;

    RunPhase(String polishLabel) {
        this.polishLabel = polishLabel;
    }

    /** Player- and admin-facing Polish name of the phase. */
    public String polishLabel() {
        return polishLabel;
    }

    public static RunPhase parse(String raw, RunPhase fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return RunPhase.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    /**
     * True while the outpost region hosts a live encounter: teleport blocking, displays and loot
     * access are all bound to this.
     */
    public boolean isLive() {
        return this == WAITING || this == PREPARATION || this == WAVES || this == BOSS
                || this == COMPLETED || this == LOOTING;
    }

    /** True while outpost mobs / the boss may exist in the world. */
    public boolean hasCombat() {
        return this == PREPARATION || this == WAVES || this == BOSS;
    }

    /**
     * True while a foreign town may still take the outpost. After the victory the controlling
     * town is frozen so the loot phase cannot be sniped.
     */
    public boolean allowsControlChange() {
        return this == WAITING || this == PREPARATION || this == WAVES || this == BOSS;
    }

    /** True when the run has ended successfully and the winner is fixed. */
    public boolean isWon() {
        return this == COMPLETED || this == LOOTING;
    }
}
