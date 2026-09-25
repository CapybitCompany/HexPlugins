package hexposterunki;

/**
 * Explicit bootstrap lifecycle of the plugin.
 *
 * <p>The previous version had only "operational or not", which meant a plugin that started with a
 * blocker (for example {@code enabled: false}) never ran its database initialisation, and a later
 * {@code /posterunki reload} just started the scheduler while the engine sat in RECOVERING forever.
 */
public enum RuntimeState {

    /**
     * Services exist, but the stored state has not been loaded - the bootstrap has not started yet or
     * no database is available. Only the regions from {@code outposts.yml} are protected.
     */
    NOT_INITIALIZED("nie zainicjalizowane"),

    /** Table creation and loading of the stored state are running; a second bootstrap is refused. */
    INITIALIZING("inicjalizacja w toku"),

    /** Fully initialised and ticking. */
    RUNNING("działa"),

    /**
     * The stored state is loaded and its geometry protected, but the event operation is stopped - an
     * operation blocker ({@code enabled: false}, an empty catalog, an unavailable integration, invalid
     * waves) or a failed run that waits for an admin reset. A requested cleanup still finishes; a
     * persistence pause is reported by the engine's operating mode instead.
     */
    PAUSED("wstrzymane"),

    /** Loading the stored state failed (database unreachable); a reload tries again. */
    FAILED("błąd inicjalizacji");

    private final String polishLabel;

    RuntimeState(String polishLabel) {
        this.polishLabel = polishLabel;
    }

    public String polishLabel() {
        return polishLabel;
    }

    public boolean initialized() {
        return this == RUNNING || this == PAUSED;
    }
}
