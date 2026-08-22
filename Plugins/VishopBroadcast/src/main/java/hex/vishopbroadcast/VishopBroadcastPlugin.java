package hex.vishopbroadcast;

import hex.core.api.HexApi;
import hex.vishopbroadcast.command.VishopBroadcastCommand;
import hex.vishopbroadcast.config.VishopConfigLoader;
import hex.vishopbroadcast.config.VishopSettings;
import hex.vishopbroadcast.database.PurchaseRepository;
import hex.vishopbroadcast.service.PurchaseBroadcastService;
import hex.vishopbroadcast.text.PurchaseTextFactory;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class VishopBroadcastPlugin extends JavaPlugin {
    private HexApi api;
    private VishopSettings settings;
    private PurchaseRepository repository;
    private PurchaseBroadcastService broadcastService;
    private final PurchaseTextFactory textFactory = new PurchaseTextFactory();

    @Override
    public void onEnable() {
        RegisteredServiceProvider<HexApi> registration = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (registration == null) {
            getLogger().severe("HexCore not found! Disabling VishopBroadcast.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.api = registration.getProvider();
        this.settings = VishopConfigLoader.load(this);
        this.repository = new PurchaseRepository(api.db().db(), settings);
        this.broadcastService = new PurchaseBroadcastService(this, api, repository, this::settings, textFactory);

        registerCommand();
        initializeDatabaseAndStart(true);
        getLogger().info("VishopBroadcast enabled.");
    }

    @Override
    public void onDisable() {
        if (broadcastService != null) {
            broadcastService.stop();
        }
    }

    public void reloadPlugin() {
        if (broadcastService != null) {
            broadcastService.stop();
        }
        this.settings = VishopConfigLoader.load(this);
        this.repository = new PurchaseRepository(api.db().db(), settings);
        this.broadcastService = new PurchaseBroadcastService(this, api, repository, this::settings, textFactory);
        initializeDatabaseAndStart(false);
    }

    public VishopSettings settings() {
        return settings;
    }

    public PurchaseRepository repository() {
        return repository;
    }

    private void registerCommand() {
        VishopBroadcastCommand executor = new VishopBroadcastCommand(this, textFactory);
        PluginCommand command = getCommand("vishopbroadcast");
        if (command == null) {
            getLogger().severe("Command vishopbroadcast is missing from plugin.yml");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void initializeDatabaseAndStart(boolean pluginStartup) {
        api.db().async(() -> {
                    if (settings.skipExistingLogsOnStartup() || !pluginStartup) {
                        return repository.maxLogId();
                    }
                    return 0L;
                })
                .thenAccept(initialLastSeenId -> Bukkit.getScheduler().runTask(this, () -> {
                    if (!isEnabled()) {
                        return;
                    }
                    broadcastService.start(initialLastSeenId == null ? 0L : initialLastSeenId);
                    getLogger().info("VishopBroadcast reader ready. Last seen log id: " + (initialLastSeenId == null ? 0L : initialLastSeenId));
                }))
                .exceptionally(ex -> {
                    getLogger().severe("VishopBroadcast database initialization failed: " + ex.getMessage());
                    Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
                    return null;
                });
    }
}

