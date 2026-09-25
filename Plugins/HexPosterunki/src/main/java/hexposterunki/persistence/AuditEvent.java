package hexposterunki.persistence;

/** Audit event names. Individual mob kills are deliberately absent - only aggregates are stored. */
public final class AuditEvent {

    public static final String OUTPOST_SELECTED = "OUTPOST_SELECTED";
    public static final String RUN_STARTED = "RUN_STARTED";
    public static final String RUN_RESET = "RUN_RESET";
    public static final String RUN_FAILED = "RUN_FAILED";
    public static final String TOWN_CLAIMED = "TOWN_CLAIMED";
    public static final String TOWN_TAKEOVER = "TOWN_TAKEOVER";
    public static final String PLAYER_PROGRESS_LOST = "PLAYER_PROGRESS_LOST";
    public static final String BOSS_ROLLED = "BOSS_ROLLED";
    public static final String BOSS_SPAWNED = "BOSS_SPAWNED";
    public static final String BOSS_REATTACHED = "BOSS_REATTACHED";
    public static final String BOSS_MISSING = "BOSS_MISSING";
    public static final String BOSS_DIED = "BOSS_DIED";
    public static final String WAVE_STARTED = "WAVE_STARTED";
    public static final String LOOTING_STARTED = "LOOTING_STARTED";
    public static final String LOOT_UNCERTAIN = "LOOT_UNCERTAIN";
    public static final String COMPLETED = "COMPLETED";
    public static final String REWARD_CLAIMED = "REWARD_CLAIMED";
    public static final String REWARD_GRANTED = "REWARD_GRANTED";
    public static final String REWARD_FAILED = "REWARD_FAILED";
    public static final String REWARD_RESOLVED = "REWARD_RESOLVED";
    public static final String RECOVERY = "RECOVERY";
    public static final String PERSISTENCE_PAUSED = "PERSISTENCE_PAUSED";
    public static final String PERSISTENCE_RESUMED = "PERSISTENCE_RESUMED";
    public static final String ADMIN_ACTION = "ADMIN_ACTION";

    private AuditEvent() {
    }
}
