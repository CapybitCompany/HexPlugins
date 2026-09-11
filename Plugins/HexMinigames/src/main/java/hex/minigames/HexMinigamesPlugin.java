package hex.minigames;

import hex.events.api.ModuleRegistration;
import hex.minigames.command.HexMinigamesCommand;
import hex.minigames.config.LoadedMinigamesConfig;
import hex.minigames.config.MinigamesConfigLoader;
import hex.minigames.event.HexEventsBridge;
import hex.minigames.event.MinigamesEventModule;
import hex.minigames.game.DebugMinigame;
import hex.minigames.game.MinigameFactory;
import hex.minigames.game.MinigameRegistry;
import hex.minigames.game.supermemory.SuperMemoryConfig;
import hex.minigames.game.supermemory.SuperMemoryMinigame;
import hex.minigames.listener.MinigamesEventRouter;
import hex.minigames.persistence.PlayerSnapshotRepository;
import hex.minigames.score.MinigamesScoreRepository;
import hex.minigames.score.ScoreService;
import hex.minigames.score.ScoreStorageFactory;
import hex.minigames.runtime.MinigamesSessionService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class HexMinigamesPlugin extends JavaPlugin implements Listener {
    private LoadedMinigamesConfig config;
    private MinigameRegistry registry;
    private PlayerSnapshotRepository snapshots;
    private ScoreService scores;
    private MinigamesSessionService sessions;
    private ModuleRegistration moduleRegistration;

    @Override
    public void onEnable() {
        MinigamesConfigLoader loader = new MinigamesConfigLoader(this);
        loader.saveDefaults();
        config = loader.load();

        registry = new MinigameRegistry();
        registry.register(new MinigameFactory() {
            @Override
            public String id() {
                return DebugMinigame.ID;
            }

            @Override
            public boolean internal() {
                return true;
            }

            @Override
            public hex.minigames.game.Minigame create() {
                return new DebugMinigame();
            }
        });
        registry.register(new MinigameFactory() {
            @Override
            public String id() {
                return SuperMemoryConfig.ID;
            }

            @Override
            public boolean internal() {
                return false;
            }

            @Override
            public hex.minigames.game.Minigame create() {
                return new SuperMemoryMinigame(HexMinigamesPlugin.this);
            }
        });
        registry.rebuild(config.gameDefinitions());

        snapshots = new PlayerSnapshotRepository(this);
        snapshots.initialize();

        MinigamesScoreRepository repository = ScoreStorageFactory.create(this, config.global().storageType(), config.global().sqliteFile());
        scores = new ScoreService(this, repository);

        sessions = new MinigamesSessionService(this, snapshots, scores, registry);
        sessions.configure(config);
        ensureConfiguredWorldLoaded();
        logModuleAvailability("after configured world check");

        HexEventsBridge bridge = HexEventsBridge.create(this);
        if (bridge == null) {
            getLogger().severe("HexEventsApi unavailable; disabling HexMinigames.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        sessions.eventsBridge(bridge);
        moduleRegistration = bridge.registerModule(new MinigamesEventModule(sessions));

        getServer().getPluginManager().registerEvents(new MinigamesEventRouter(sessions), this);
        getServer().getPluginManager().registerEvents(this, this);

        HexMinigamesCommand command = new HexMinigamesCommand(() -> config, sessions, registry, this::reloadMinigames);
        getCommand("hexminigames").setExecutor(command);
        getCommand("hexminigames").setTabCompleter(command);

        sessions.startTicking();
        for (Player player : Bukkit.getOnlinePlayers()) {
            sessions.handlePendingJoin(player);
        }
        getLogger().info("HexMinigames enabled. availability=" + (sessions.available() ? "AVAILABLE" : sessions.availabilityReason()));
    }

    @Override
    public void onDisable() {
        if (moduleRegistration != null) {
            moduleRegistration.close();
            moduleRegistration = null;
        }
        if (sessions != null) {
            sessions.shutdown();
            sessions = null;
        }
        scores = null;
        snapshots = null;
        registry = null;
    }

    public void reloadMinigames() {
        MinigamesConfigLoader loader = new MinigamesConfigLoader(this);
        loader.saveDefaults();
        LoadedMinigamesConfig loaded = loader.load();
        config = loaded;
        if (sessions != null) sessions.configure(loaded);
        if (!loaded.valid()) {
            for (String error : loaded.errors()) getLogger().warning(error);
            for (String error : loaded.global().errors()) getLogger().warning(error);
        }
    }

    private World ensureConfiguredWorldLoaded() {
        if (config == null || config.global().worldName() == null || config.global().worldName().isBlank()) return null;

        String worldName = config.global().worldName();
        File worldFolder = new File(getServer().getWorldContainer(), worldName);
        File levelDat = new File(worldFolder, "level.dat");
        if (!worldFolder.isDirectory()) {
            getLogger().severe("Configured HexMinigames world folder does not exist; not creating a new world: "
                    + worldFolder.getAbsolutePath());
            return null;
        }
        if (!levelDat.isFile()) {
            getLogger().severe("Configured HexMinigames world folder is missing level.dat; not creating a new world: "
                    + worldFolder.getAbsolutePath());
            return null;
        }

        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null) return loaded;

        try {
            World world = Bukkit.createWorld(new WorldCreator(worldName));
            if (world == null) {
                getLogger().severe("Bukkit returned null while loading existing HexMinigames world '" + worldName
                        + "'; module remains unavailable.");
                return null;
            }
            getLogger().info("Loaded existing HexMinigames world: " + world.getName());
            return world;
        } catch (Throwable error) {
            getLogger().severe("Could not load existing HexMinigames world '" + worldName
                    + "'; module remains unavailable: " + rootMessage(error));
            return null;
        }
    }

    private void logModuleAvailability(String stage) {
        if (sessions == null) return;
        boolean available = sessions.available();
        getLogger().info("hex:minigames availability " + stage + ": "
                + (available ? "AVAILABLE" : "UNAVAILABLE")
                + (available ? "" : ", reason=" + sessions.availabilityReason()));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @EventHandler
    public void onDependencyDisable(PluginDisableEvent event) {
        if (!event.getPlugin().getName().equalsIgnoreCase("HexEvents")) return;
        getLogger().severe("HexEvents disabled while HexMinigames is active; restoring all active players.");
        if (moduleRegistration != null) {
            moduleRegistration.close();
            moduleRegistration = null;
        }
        if (sessions != null) sessions.shutdown();
    }
}
