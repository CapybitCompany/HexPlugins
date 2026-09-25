package hexposterunki.ui;

import java.util.List;

/**
 * Template keys of the {@code posterunki} HexCore UI namespace.
 *
 * <p>Every constant is the <b>fully qualified</b> key. HexCore's {@code TemplateRegistry} treats a
 * key that already contains a dot as complete and does <i>not</i> prepend the namespace, so
 * registering {@code "announce.activated"} while rendering {@code "posterunki.announce.activated"}
 * silently produced "Missing template". Registration and rendering now use exactly these
 * constants, and {@link #ALL} lets a test assert that every one of them resolves.
 */
public final class UiKeys {

    public static final String NAMESPACE = "posterunki";

    private static String key(String name) {
        return NAMESPACE + "." + name;
    }

    public static final String ANNOUNCE_ACTIVATED = key("announce.activated");
    public static final String ANNOUNCE_EXPIRED = key("announce.expired");
    public static final String ANNOUNCE_COMPLETED = key("announce.completed");
    public static final String ANNOUNCE_WIPED = key("announce.wiped");
    public static final String ANNOUNCE_FAILED = key("announce.failed");

    public static final String CLAIM_BROADCAST = key("claim.broadcast");
    public static final String CLAIM_TITLE = key("claim.title");
    public static final String CLAIM_SUBTITLE = key("claim.subtitle");

    public static final String TAKEOVER_BROADCAST = key("takeover.broadcast");
    public static final String TAKEOVER_TITLE = key("takeover.title");
    public static final String TAKEOVER_SUBTITLE = key("takeover.subtitle");

    public static final String WAVE_STARTED = key("wave.started");
    public static final String WAVE_CLEARED = key("wave.cleared");

    public static final String BOSS_TITLE = key("boss.title");
    public static final String BOSS_SUBTITLE = key("boss.subtitle");
    public static final String BOSS_BROADCAST = key("boss.broadcast");
    public static final String BOSS_DEFEATED = key("boss.defeated");

    public static final String VICTORY_TITLE = key("victory.title");
    public static final String VICTORY_SUBTITLE = key("victory.subtitle");

    public static final String LOOTING_BROADCAST = key("looting.broadcast");
    public static final String LOOTING_ACTIONBAR = key("looting.actionbar");
    public static final String LOOTING_ENDED = key("looting.ended");

    public static final String PROGRESS_LOST_LEAVE = key("progress.lost.leave");
    public static final String PROGRESS_LOST_DEATH = key("progress.lost.death");
    public static final String PROGRESS_LOST_WORLD = key("progress.lost.world");
    public static final String PROGRESS_LOST_TELEPORT = key("progress.lost.teleport");

    public static final String TELEPORT_BLOCKED = key("teleport.blocked");
    public static final String NO_TOWN = key("error.no-town");
    public static final String NOT_CONTROLLING = key("error.not-controlling");
    public static final String NOT_PARTICIPATING = key("error.not-participating");
    public static final String SAME_TOWN_PVP = key("error.same-town-pvp");
    public static final String REGION_PROTECTED = key("error.region-protected");
    public static final String CONTAINER_LOCKED = key("error.container-locked");
    public static final String CONTAINER_NOT_YOURS = key("error.container-not-yours");
    public static final String EVENT_PAUSED = key("error.event-paused");

    public static final String ACTIONBAR_STATUS = key("actionbar.status");
    public static final String BOSSBAR_TEXT = key("bossbar.text");
    public static final String HOLOGRAM_TEXT = key("hologram.text");

    public static final String REWARD_GRANTED = key("reward.granted");
    public static final String REWARD_PENDING_REVIEW = key("reward.pending-review");
    public static final String REWARD_NOT_ELIGIBLE = key("reward.not-eligible");

    public static final String ADMIN_STATUS_HEADER = key("admin.status.header");
    public static final String ADMIN_STATUS_LINE = key("admin.status.line");
    public static final String ADMIN_VALIDATE_OK = key("admin.validate.ok");
    public static final String ADMIN_VALIDATE_PROBLEM = key("admin.validate.problem");
    public static final String ADMIN_STARTED = key("admin.started");
    public static final String ADMIN_STOPPED = key("admin.stopped");
    public static final String ADMIN_RESET = key("admin.reset");
    public static final String ADMIN_RELOADED = key("admin.reloaded");
    public static final String ADMIN_ERROR = key("admin.error");
    public static final String ADMIN_USAGE = key("admin.usage");
    public static final String ADMIN_NO_PERMISSION = key("admin.no-permission");
    public static final String ADMIN_REWARD_HEADER = key("admin.reward.header");
    public static final String ADMIN_REWARD_LINE = key("admin.reward.line");
    public static final String ADMIN_REWARD_EMPTY = key("admin.reward.empty");
    public static final String ADMIN_REWARD_RESOLVED = key("admin.reward.resolved");
    public static final String ADMIN_REWARD_RETRY = key("admin.reward.retry");
    public static final String ADMIN_REWARD_UNKNOWN = key("admin.reward.unknown");

    /** Every key the plugin renders. Used by the registration test to prove none is missing. */
    public static final List<String> ALL = List.of(
            ANNOUNCE_ACTIVATED, ANNOUNCE_EXPIRED, ANNOUNCE_COMPLETED, ANNOUNCE_WIPED, ANNOUNCE_FAILED,
            CLAIM_BROADCAST, CLAIM_TITLE, CLAIM_SUBTITLE,
            TAKEOVER_BROADCAST, TAKEOVER_TITLE, TAKEOVER_SUBTITLE,
            WAVE_STARTED, WAVE_CLEARED,
            BOSS_TITLE, BOSS_SUBTITLE, BOSS_BROADCAST, BOSS_DEFEATED,
            VICTORY_TITLE, VICTORY_SUBTITLE,
            LOOTING_BROADCAST, LOOTING_ACTIONBAR, LOOTING_ENDED,
            PROGRESS_LOST_LEAVE, PROGRESS_LOST_DEATH, PROGRESS_LOST_WORLD, PROGRESS_LOST_TELEPORT,
            TELEPORT_BLOCKED, NO_TOWN, NOT_CONTROLLING, NOT_PARTICIPATING, SAME_TOWN_PVP,
            REGION_PROTECTED, CONTAINER_LOCKED, CONTAINER_NOT_YOURS, EVENT_PAUSED,
            ACTIONBAR_STATUS, BOSSBAR_TEXT, HOLOGRAM_TEXT,
            REWARD_GRANTED, REWARD_PENDING_REVIEW, REWARD_NOT_ELIGIBLE,
            ADMIN_STATUS_HEADER, ADMIN_STATUS_LINE, ADMIN_VALIDATE_OK, ADMIN_VALIDATE_PROBLEM,
            ADMIN_STARTED, ADMIN_STOPPED, ADMIN_RESET, ADMIN_RELOADED, ADMIN_ERROR, ADMIN_USAGE,
            ADMIN_NO_PERMISSION, ADMIN_REWARD_HEADER, ADMIN_REWARD_LINE, ADMIN_REWARD_EMPTY,
            ADMIN_REWARD_RESOLVED, ADMIN_REWARD_RETRY, ADMIN_REWARD_UNKNOWN);

    private UiKeys() {
    }
}
