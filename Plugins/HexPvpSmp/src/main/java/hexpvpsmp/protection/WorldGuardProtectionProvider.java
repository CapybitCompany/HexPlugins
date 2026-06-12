package hexpvpsmp.protection;

import hexpvpsmp.region.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.logging.Logger;

/**
 * v1 stub. Detects whether WorldGuard is loaded via the plugin manager
 * (no compile-time dependency, no classloader probes that would fail if the
 * jar is absent). Returns no regions: real WorldGuard integration is a
 * future task, kept here so the provider abstraction is exercised today.
 */
public final class WorldGuardProtectionProvider implements ProtectionProvider {

    private static final String PLUGIN_NAME = "WorldGuard";

    private final Logger logger;
    private boolean warned;

    public WorldGuardProtectionProvider(Logger logger) {
        this.logger = logger;
    }

    @Override
    public boolean isAvailable() {
        // Available only if WorldGuard is loaded AND the integration is wired.
        // The wiring step doesn't exist yet, so we return false unconditionally.
        Plugin wg = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (wg != null && wg.isEnabled() && !warned) {
            logger.info("HexPvpSmp: WorldGuard detected but the integration is not wired in v1.");
            warned = true;
        }
        return false;
    }

    @Override
    public List<ProtectedRegion> regionsAt(String worldName, double x, double y, double z) {
        return List.of();
    }

    @Override
    public List<ProtectedRegion> allRegions() {
        return List.of();
    }
}
