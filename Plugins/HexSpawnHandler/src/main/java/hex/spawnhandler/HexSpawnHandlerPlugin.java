package hex.spawnhandler;

import hex.spawnhandler.listener.JoinSpawnListener;
import hex.spawnhandler.model.SpawnSettings;
import hex.spawnhandler.service.SpawnSettingsService;
import org.bukkit.plugin.java.JavaPlugin;

public final class HexSpawnHandlerPlugin extends JavaPlugin {

    private SpawnSettingsService spawnSettingsService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.spawnSettingsService = new SpawnSettingsService(this);
        this.spawnSettingsService.loadFromConfig();

        getServer().getPluginManager().registerEvents(new JoinSpawnListener(this, spawnSettingsService), this);

        SpawnSettings settings = spawnSettingsService.getSettings();
        getLogger().info(
                "HexSpawnHandler enabled. Spawn=" + settings.x() + ", " + settings.y() + ", " + settings.z()
                        + " world=" + (settings.worldName() == null ? "<join-world>" : settings.worldName())
                        + " delayTicks=" + settings.teleportDelayTicks()
                        + " finalTeleport=" + settings.finalTeleportEnabled()
                        + " finalDelayTicks=" + settings.finalTeleportDelayTicks()
        );
    }
}
