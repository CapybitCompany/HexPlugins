package hexchests;

import hexchests.command.HexChestsCommand;
import hexchests.config.HexChestsConfig;
import hexchests.config.HexChestsConfigLoader;
import hexchests.listener.HexChestsListener;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicReference;

public class HexChestsPlugin extends JavaPlugin {

    private final HexChestsConfigLoader configLoader = new HexChestsConfigLoader();
    private final AtomicReference<HexChestsConfig> configRef = new AtomicReference<>();

    private KeyService keyService;
    private ChestService chestService;
    private HexChestsListener listener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!reloadPluginConfig()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.keyService = new KeyService(this, configRef::get);
        this.chestService = new ChestService(this, configRef::get, keyService);
        this.listener = new HexChestsListener(chestService);
        getServer().getPluginManager().registerEvents(listener, this);

        HexChestsCommand command = new HexChestsCommand(this);
        var pluginCommand = getCommand("hexchests");
        if (pluginCommand == null) {
            getLogger().severe("Command 'hexchests' missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        getLogger().info("HexChests enabled.");
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        if (chestService != null) {
            chestService.stop();
            chestService = null;
        }
        keyService = null;
        getLogger().info("HexChests disabled.");
    }

    public boolean reloadPluginRuntime() {
        reloadConfig();
        return reloadPluginConfig();
    }

    public HexChestsConfig config() {
        return configRef.get();
    }

    public KeyService keyService() {
        return keyService;
    }

    public ChestService chestService() {
        return chestService;
    }

    private boolean reloadPluginConfig() {
        try {
            configRef.set(configLoader.load(getConfig(), getLogger()));
            return true;
        } catch (RuntimeException ex) {
            getLogger().severe("Failed to load HexChests config: " + ex.getMessage());
            return false;
        }
    }
}
