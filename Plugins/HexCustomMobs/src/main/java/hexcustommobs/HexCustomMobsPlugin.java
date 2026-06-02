package hexcustommobs;

import hexcustommobs.command.HexCustomMobsCommand;
import hexcustommobs.config.HexCustomMobsConfig;
import hexcustommobs.config.HexCustomMobsConfigLoader;
import hexcustommobs.integration.HexCustomItemsBridge;
import hexcustommobs.listener.CustomMobDeathListener;
import hexcustommobs.listener.CustomMobHealthListener;
import hexcustommobs.listener.CustomMobSpawnListener;
import hexcustommobs.service.CustomMobService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicReference;

public final class HexCustomMobsPlugin extends JavaPlugin {

    private final AtomicReference<HexCustomMobsConfig> configRef = new AtomicReference<>();
    private final HexCustomMobsConfigLoader configLoader = new HexCustomMobsConfigLoader();

    private CustomMobService customMobService;
    private HexCustomItemsBridge customItemsBridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!initializeRuntime()) {
            getLogger().severe("HexCustomMobs nie mógł wystartować. Wyłączam plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        registerListeners();
        registerCommand();
        getLogger().info("HexCustomMobs uruchomiony.");
    }

    @Override
    public void onDisable() {
        getLogger().info("HexCustomMobs zatrzymany.");
    }

    public boolean reloadPluginRuntime() {
        try {
            reloadConfig();
            HexCustomMobsConfig loaded = configLoader.load(getConfig(), getLogger());
            configRef.set(loaded);
            return true;
        } catch (Exception exception) {
            getLogger().severe("Błąd przy reload: " + exception.getMessage());
            exception.printStackTrace();
            return false;
        }
    }

    public HexCustomMobsConfig config() {
        return configRef.get();
    }

    private boolean initializeRuntime() {
        try {
            HexCustomMobsConfig loaded = configLoader.load(getConfig(), getLogger());
            configRef.set(loaded);
            this.customItemsBridge = new HexCustomItemsBridge(getLogger());
            this.customMobService = new CustomMobService(this, configRef::get, customItemsBridge);
            if (customItemsBridge.isAvailable()) {
                getLogger().info("Wykryto HexCustomItems - dropy/equipment z custom-item-id są aktywne.");
            } else {
                getLogger().info("HexCustomItems nie jest aktywny - custom-item-id będą pomijane.");
            }
            return true;
        } catch (Exception exception) {
            getLogger().severe("Błąd konfiguracji: " + exception.getMessage());
            exception.printStackTrace();
            return false;
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new CustomMobSpawnListener(this, configRef::get, customMobService),
                this
        );
        getServer().getPluginManager().registerEvents(
                new CustomMobDeathListener(customMobService),
                this
        );
        getServer().getPluginManager().registerEvents(
                new CustomMobHealthListener(this, customMobService),
                this
        );
    }

    private void registerCommand() {
        PluginCommand command = getCommand("hexcustommobs");
        if (command == null) {
            getLogger().warning("Brak komendy 'hexcustommobs' w plugin.yml.");
            return;
        }
        HexCustomMobsCommand executor = new HexCustomMobsCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
