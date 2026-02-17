package hex.core;

import hex.core.command.HexCoreVersion;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * HexCore - main module of the general structure of the Hex plugins. Contains general
 * functions, commands and classes. It's a generic solution, shared between plugins.
 */
public final class HexCore extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("HexTest wstał ✅");
        getCommand("hexcore").setExecutor(new HexCoreVersion(this));
    }

    @Override
    public void onDisable() {
        getLogger().info("HexTest zgasł ❌");
    }
}