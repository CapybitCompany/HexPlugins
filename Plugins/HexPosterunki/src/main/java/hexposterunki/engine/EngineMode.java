package hexposterunki.engine;

/**
 * The single operating state of the encounter, shared by the scheduler tick and every event handler.
 *
 * <p>Before this existed only the tick checked persistence health, so damage, kill credits and the
 * boss completion kept running on unsaved state. Every path that can advance the fight now asks
 * {@link OutpostEngine#mode()} first. Region protection deliberately does not depend on it.
 */
public enum EngineMode {

    /** Everything is fine; the fight may progress. */
    RUNNING("działa"),

    /** Stopped by a startup/reload blocker, a technical failure or {@code enabled: false}. */
    STOPPED("zatrzymane"),

    /** The last state write failed; the fight is frozen until a write succeeds again. */
    PERSISTENCE_PAUSED("wstrzymane - zapis stanu niedostępny"),

    /** The fortress is being cleaned up; nothing from the finished run may progress. */
    RESETTING("trwa resetowanie posterunku"),

    /**
     * The grace period expired and the abandoned fight is being removed. Removing the boss can fire
     * its death event synchronously; that death must not count as a victory.
     */
    WIPING("powrót do fali 1 - posterunek opuszczony");

    private final String polishLabel;

    EngineMode(String polishLabel) {
        this.polishLabel = polishLabel;
    }

    public String polishLabel() {
        return polishLabel;
    }

    /** True when fighting, kill credits, control changes and completions are allowed. */
    public boolean allowsProgress() {
        return this == RUNNING;
    }
}
