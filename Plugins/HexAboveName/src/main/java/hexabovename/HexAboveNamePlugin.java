package hexabovename;

import hexabovename.command.HexAboveNameCommand;
import hexabovename.config.HexAboveNameConfig;
import hexabovename.config.HexAboveNameConfigLoader;
import hexabovename.config.StorageType;
import hexabovename.listener.PlayerLifecycleListener;
import hexabovename.repository.DisplayTextRepository;
import hexabovename.repository.MySqlDisplayTextRepository;
import hexabovename.repository.YamlDisplayTextRepository;
import hexabovename.service.DisplayRenderService;
import hexabovename.service.DisplayTextCacheService;
import hexabovename.service.MessageService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

public final class HexAboveNamePlugin extends JavaPlugin {

    private final HexAboveNameConfigLoader configLoader = new HexAboveNameConfigLoader();
    private final AtomicReference<HexAboveNameConfig> configRef = new AtomicReference<>();

    private MessageService messageService;
    private DisplayTextRepository repository;
    private DisplayTextCacheService cacheService;
    private DisplayRenderService renderService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureDefaultUsersFile();

        this.messageService = new MessageService(configRef::get);

        if (!initializeRuntime()) {
            getLogger().severe("Nie udało się uruchomić HexAboveName. Wyłączam plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!registerCommand()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(this), this);

        getLogger().info("HexAboveName uruchomiony.");
    }

    @Override
    public void onDisable() {
        shutdownRuntime();
        getLogger().info("HexAboveName zatrzymany.");
    }

    public boolean reloadPluginRuntime() {
        shutdownRuntime();
        reloadConfig();
        return initializeRuntime();
    }

    public HexAboveNameConfig config() {
        return configRef.get();
    }

    public DisplayTextCacheService cacheService() {
        return cacheService;
    }

    public DisplayRenderService renderService() {
        return renderService;
    }

    private boolean initializeRuntime() {
        HexAboveNameConfig loadedConfig = configLoader.load(getConfig());
        configRef.set(loadedConfig);

        try {
            this.repository = createRepository(loadedConfig);
            repository.initialize();
        } catch (Exception exception) {
            getLogger().severe("Nie udało się zainicjalizować storage: " + exception.getMessage());
            exception.printStackTrace();
            closeRepositoryQuietly();
            return false;
        }

        this.cacheService = new DisplayTextCacheService(this, getLogger(), repository);
        cacheService.start(loadedConfig.storage().refreshIntervalTicks());

        this.renderService = new DisplayRenderService(this, loadedConfig, cacheService);
        renderService.start();
        return true;
    }

    private boolean registerCommand() {
        PluginCommand command = getCommand("hexabovename");
        if (command == null) {
            getLogger().severe("Brak komendy 'hexabovename' w plugin.yml.");
            return false;
        }
        HexAboveNameCommand executor = new HexAboveNameCommand(this, messageService);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        return true;
    }

    private DisplayTextRepository createRepository(HexAboveNameConfig config) {
        if (config.storage().type() == StorageType.MYSQL) {
            return new MySqlDisplayTextRepository(config.storage().mysql());
        }
        return new YamlDisplayTextRepository(new File(getDataFolder(), config.storage().yamlFile()));
    }

    private void ensureDefaultUsersFile() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Nie udało się utworzyć folderu pluginu.");
            return;
        }
        File users = new File(getDataFolder(), "users.yml");
        if (!users.exists()) {
            saveResource("users.yml", false);
        }
    }

    private void shutdownRuntime() {
        if (renderService != null) {
            renderService.stop();
            renderService = null;
        }
        if (cacheService != null) {
            cacheService.stop();
            cacheService = null;
        }
        closeRepositoryQuietly();
    }

    private void closeRepositoryQuietly() {
        if (repository == null) {
            return;
        }
        try {
            repository.close();
        } catch (Exception exception) {
            getLogger().warning("Nie udało się zamknąć storage: " + exception.getMessage());
        } finally {
            repository = null;
        }
    }
}
