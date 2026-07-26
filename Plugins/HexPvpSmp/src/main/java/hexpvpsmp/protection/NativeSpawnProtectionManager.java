package hexpvpsmp.protection;

import org.bukkit.Server;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Controls the server's native spawn protection radius on behalf of HexPvpSmp.
 *
 * <p>HexPvpSmp owns spawn protection entirely (there is no WorldGuard). Paper
 * enforces the native {@code server.properties} {@code spawn-protection} radius
 * <b>before</b> {@link org.bukkit.event.player.PlayerInteractEvent} reaches
 * plugins, so a native radius &gt; 0 silently blocks chests, crafting tables and
 * buttons in spawn before this plugin can decide. To let this plugin be the sole
 * authority, the native radius is forced to {@code 0} at runtime via the public
 * {@link Server#getSpawnRadius()} / {@link Server#setSpawnRadius(int)} API —
 * {@code server.properties} on disk is never touched.
 *
 * <h2>Lifecycle guarantees</h2>
 * <ul>
 *   <li>The original radius is captured exactly once (the first time management
 *       is applied), so repeated reloads never mistake the forced {@code 0} for
 *       the operator's real value.</li>
 *   <li>Management is applied only after the plugin's own protection is up, so a
 *       failed start never leaves the spawn unprotected — the native radius stays
 *       untouched and keeps guarding spawn.</li>
 *   <li>On restore the original radius is put back only if this manager still
 *       controls it (the live radius is still the {@code 0} it set). If something
 *       else changed the radius meanwhile, it is left alone.</li>
 * </ul>
 *
 * <p>Not thread-safe: all calls happen on the main server thread (enable / reload
 * / disable).
 */
public final class NativeSpawnProtectionManager {

    /** The radius this manager forces while it is in control. */
    private static final int CONTROLLED_RADIUS = 0;

    private final Server server;
    private final Logger logger;

    /** The operator's radius, captured once before we first override it. */
    private Integer originalRadius;
    /** Whether we currently hold the radius at {@link #CONTROLLED_RADIUS}. */
    private boolean applied;

    public NativeSpawnProtectionManager(Server server, Logger logger) {
        this.server = Objects.requireNonNull(server, "server");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Applies the configured policy.
     *
     * @param disableNative when {@code true} the native radius is forced to 0 and
     *                      kept there; when {@code false} any radius this manager
     *                      previously forced is restored and, if the operator's
     *                      native radius is still &gt; 0, a diagnostic warns that
     *                      native protection may shadow this plugin in spawn.
     */
    public void apply(boolean disableNative) {
        if (disableNative) {
            enable();
        } else {
            leaveNativeEnabled();
        }
    }

    private void enable() {
        int current = server.getSpawnRadius();
        if (originalRadius == null) {
            originalRadius = current; // capture the operator's value exactly once
        }
        if (current != CONTROLLED_RADIUS) {
            server.setSpawnRadius(CONTROLLED_RADIUS);
        }
        boolean firstApply = !applied;
        applied = true;
        if (firstApply) {
            logger.info(String.format(
                    "Native spawn protection is managed by HexPvpSmp: original server spawn radius = %d, "
                            + "effective radius = %d (this plugin is the sole spawn-protection authority).",
                    originalRadius, CONTROLLED_RADIUS));
        }
    }

    private void leaveNativeEnabled() {
        if (applied) {
            // Admin turned management off during a reload — hand the radius back.
            restore();
        }
        int current = server.getSpawnRadius();
        if (current > 0) {
            logger.warning(String.format(
                    "disable-native-spawn-protection=false and the native server spawn radius = %d (>0). "
                            + "Paper's native spawn protection may block chests, crafting tables and buttons "
                            + "in spawn before HexPvpSmp can allow them.",
                    current));
        } else {
            logger.info("disable-native-spawn-protection=false; native server spawn radius left at "
                    + current + ".");
        }
    }

    /**
     * Restores the operator's original radius, but only while this manager still
     * controls the value. Safe to call when nothing was ever applied (no-op) and
     * idempotent. Used on disable and as a safety fallback when a reload fails
     * with the plugin's own protection down.
     */
    public void restore() {
        if (!applied) {
            return;
        }
        applied = false;
        int current = server.getSpawnRadius();
        if (current != CONTROLLED_RADIUS) {
            logger.warning(String.format(
                    "Native server spawn radius was changed externally to %d after HexPvpSmp set it to %d; "
                            + "leaving it unchanged (no longer under this plugin's control).",
                    current, CONTROLLED_RADIUS));
            return;
        }
        int restoreTo = originalRadius != null ? originalRadius : CONTROLLED_RADIUS;
        server.setSpawnRadius(restoreTo);
        logger.info("Restored native server spawn radius to " + restoreTo + ".");
    }

    /** Whether this manager currently holds the native radius at {@code 0}. */
    public boolean isManaging() {
        return applied;
    }

    /** The operator's captured original radius, or {@code null} if never applied. */
    public Integer originalRadius() {
        return originalRadius;
    }
}
