package hex.flood;

import hex.flood.command.FloodCommand;
import hex.flood.config.FloodConfig;
import hex.flood.manager.FloodManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * WaterLevelFlood - plugin odpowiedzialny tylko za podnoszenie poziomu wody.
 * Mechanika tonięcia została wydzielona do osobnego pluginu WaterDrawn.
 */
public final class WaterLevelFloodPlugin extends JavaPlugin {

    private FloodConfig floodConfig;
    private FloodManager floodManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.floodConfig = new FloodConfig(this);
        this.floodManager = new FloodManager(this, floodConfig);

        var floodCmd = getCommand("flood");
        if (floodCmd != null) {
            var command = new FloodCommand(this, floodConfig, floodManager);
            floodCmd.setExecutor(command);
            floodCmd.setTabCompleter(command);
        }

        getLogger().info("WaterLevelFlood zaladowany. Uzyj /flood start aby rozpoczac event.");
    }

    @Override
    public void onDisable() {
        if (floodManager != null && floodManager.isRunning()) {
            floodManager.stop();
        }
        getLogger().info("WaterLevelFlood wylaczony.");
    }

    public FloodConfig getFloodConfig() {
        return floodConfig;
    }

    public FloodManager getFloodManager() {
        return floodManager;
    }

    public void reloadFloodConfig() {
        reloadConfig();
        this.floodConfig = new FloodConfig(this);
        this.floodManager.updateConfig(floodConfig);
        getLogger().info("Konfiguracja WaterLevelFlood przeladowana.");
    }
}
