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
import hex.minigames.game.dalgona.DalgonaConfig;
import hex.minigames.game.dalgona.DalgonaMinigame;
import hex.minigames.game.glassbridge.GlassBridgeConfig;
import hex.minigames.game.glassbridge.GlassBridgeMinigame;
import hex.minigames.game.hothead.HotHeadConfig;
import hex.minigames.game.hothead.HotHeadMinigame;
import hex.minigames.game.popcorn.PopcornConfig;
import hex.minigames.game.popcorn.PopcornMinigame;
import hex.minigames.game.redlight.RedLightGreenLightConfig;
import hex.minigames.game.redlight.RedLightGreenLightMinigame;
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
    private hex.minigames.score.MinigamesPlaceholderExpansion placeholders;
    private hex.minigames.runtime.SeriesCelebration celebration;

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
        registry.register(new MinigameFactory() {
            @Override
            public String id() {
                return RedLightGreenLightConfig.ID;
            }

            @Override
            public boolean internal() {
                return false;
            }

            @Override
            public hex.minigames.game.Minigame create() {
                return new RedLightGreenLightMinigame(HexMinigamesPlugin.this);
            }
        });
        registry.register(new MinigameFactory() {
            @Override
            public String id() {
                return HotHeadConfig.ID;
            }

            @Override
            public boolean internal() {
                return false;
            }

            @Override
            public hex.minigames.game.Minigame create() {
                return new HotHeadMinigame(HexMinigamesPlugin.this);
            }
        });
        registry.register(new MinigameFactory() {
            @Override
            public String id() {
                return DalgonaConfig.ID;
            }

            @Override
            public boolean internal() {
                return false;
            }

            @Override
            public hex.minigames.game.Minigame create() {
                return new DalgonaMinigame(HexMinigamesPlugin.this);
            }
        });
        registry.register(new MinigameFactory() {
            @Override
            public String id() {
                return GlassBridgeConfig.ID;
            }

            @Override
            public boolean internal() {
                return false;
            }

            @Override
            public hex.minigames.game.Minigame create() {
                return new GlassBridgeMinigame(HexMinigamesPlugin.this);
            }
        });
        registry.register(new MinigameFactory() {
            @Override
            public String id() {
                return PopcornConfig.ID;
            }

            @Override
            public boolean internal() {
                return false;
            }

            @Override
            public hex.minigames.game.Minigame create() {
                return new PopcornMinigame(HexMinigamesPlugin.this);
            }
        });
        registry.register(new MinigameFactory() {
            @Override
            public String id() { return hex.minigames.game.breezetower.BreezeTowerConfig.ID; }

            @Override
            public boolean internal() { return false; }

            @Override
            public hex.minigames.game.Minigame create() {
                return new hex.minigames.game.breezetower.BreezeTowerMinigame(HexMinigamesPlugin.this);
            }
        });
        registry.register(new MinigameFactory() {
            @Override public String id() { return hex.minigames.game.tag.TagConfig.ID; }
            @Override public boolean internal() { return false; }
            @Override public hex.minigames.game.Minigame create() {
                return new hex.minigames.game.tag.TagMinigame(HexMinigamesPlugin.this);
            }
        });
        registry.register(new MinigameFactory() {
            @Override public String id() { return hex.minigames.game.jumprope.JumpRopeConfig.ID; }
            @Override public boolean internal() { return false; }
            @Override public hex.minigames.game.Minigame create() {
                return new hex.minigames.game.jumprope.JumpRopeMinigame(HexMinigamesPlugin.this);
            }
        });
        registry.register(new MinigameFactory() {
            @Override public String id() { return "disco_floor"; }
            @Override public boolean internal() { return false; }
            @Override public hex.minigames.game.Minigame create() {
                return new hex.minigames.game.discofloor.DiscoFloorMinigame(HexMinigamesPlugin.this);
            }
        });
        registry.register(new MinigameFactory() {
            @Override public String id() { return "monkey_run"; }
            @Override public boolean internal() { return false; }
            @Override public hex.minigames.game.Minigame create() { return new hex.minigames.game.sumo.SumoMinigame(HexMinigamesPlugin.this); }
        });
        registry.register(new MinigameFactory() {
            @Override public String id() { return "elytra"; }
            @Override public boolean internal() { return false; }
            @Override public hex.minigames.game.Minigame create() { return new hex.minigames.game.elytra.ElytraMinigame(HexMinigamesPlugin.this); }
        });
        registry.register(new MinigameFactory() {
            @Override public String id() { return "drones"; }
            @Override public boolean internal() { return false; }
            @Override public hex.minigames.game.Minigame create() { return new hex.minigames.game.drones.DronesMinigame(HexMinigamesPlugin.this); }
        });
        registry.rebuild(config.gameDefinitions());

        snapshots = new PlayerSnapshotRepository(this);
        snapshots.initialize();

        MinigamesScoreRepository repository = ScoreStorageFactory.create(this, config.global().storageType(), config.global().sqliteFile());
        scores = new ScoreService(this, repository);

        sessions = new MinigamesSessionService(this, snapshots, scores, registry);
        sessions.configure(config);
        ensureConfiguredWorldLoaded();
        try { hex.minigames.game.elytra.ElytraMarkerJournal.recover(this); }
        catch (IllegalStateException error) { getLogger().log(java.util.logging.Level.SEVERE, "Elytra marker recovery is pending", error); }
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

        celebration = new hex.minigames.runtime.SeriesCelebration(this);
        getServer().getPluginManager().registerEvents(celebration, this);
        sessions.celebration(celebration);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholders = new hex.minigames.score.MinigamesPlaceholderExpansion(this, scores);
            placeholders.register();
        }
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
        if (placeholders != null) { placeholders.unregister(); placeholders = null; }
        if (celebration != null) { celebration.close(); celebration = null; }
        if (scores != null) scores.close();
        scores = null;
        snapshots = null;
        registry = null;
    }

    public void reloadMinigames() {
        if (sessions != null) sessions.restoreElytraBeforeReload();
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
