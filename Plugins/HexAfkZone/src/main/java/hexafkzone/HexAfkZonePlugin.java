package hexafkzone;

import hexafkzone.command.HexAfkZoneCommand;
import hexafkzone.config.AfkZoneConfig;
import hexafkzone.config.AfkZoneConfigLoader;
import hexafkzone.listener.AfkZoneListener;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class HexAfkZonePlugin extends JavaPlugin {

    private final AfkZoneConfigLoader configLoader = new AfkZoneConfigLoader();
    private final AtomicReference<AfkZoneConfig> configRef = new AtomicReference<>();

    private AfkZoneService service;
    private AfkZoneListener listener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!reloadPluginConfig()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.service = new AfkZoneService(this, configRef::get, Clock.systemUTC());
        this.listener = new AfkZoneListener(service);
        getServer().getPluginManager().registerEvents(listener, this);
        service.start();

        HexAfkZoneCommand command = new HexAfkZoneCommand(this);
        var pluginCommand = getCommand("hexafkzone");
        if (pluginCommand == null) {
            getLogger().severe("Command 'hexafkzone' missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        getLogger().info("HexAfkZone enabled.");
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        if (service != null) {
            service.stop();
            service = null;
        }
        getLogger().info("HexAfkZone disabled.");
    }

    public boolean reloadPluginRuntime() {
        reloadConfig();
        boolean loaded = reloadPluginConfig();
        if (loaded && service != null) {
            for (var player : getServer().getOnlinePlayers()) {
                service.updatePlayer(player);
            }
        }
        return loaded;
    }

    public AfkZoneConfig config() {
        return configRef.get();
    }

    public AfkZoneService service() {
        return service;
    }

    private boolean reloadPluginConfig() {
        try {
            configRef.set(configLoader.load(getConfig(), getLogger()));
            return true;
        } catch (RuntimeException ex) {
            getLogger().severe("Failed to load HexAfkZone config: " + ex.getMessage());
            return false;
        }
    }
}
