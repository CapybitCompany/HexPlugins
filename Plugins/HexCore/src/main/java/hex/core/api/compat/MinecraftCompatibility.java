package hex.core.api.compat;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Small shared helpers for reporting the Minecraft/Paper runtime used by Hex plugins.
 */
public final class MinecraftCompatibility {
    private MinecraftCompatibility() {
    }

    public static void logStartupCompatibility(Plugin plugin) {
        if (plugin == null) {
            return;
        }

        plugin.getLogger().info("Running on " + Bukkit.getName()
                + " " + Bukkit.getBukkitVersion()
                + " (Minecraft " + Bukkit.getMinecraftVersion() + ")");
    }
}
