package hex.parkour;

import hex.events.api.ModuleRegistration;
import hex.parkour.config.ParkourConfig;
import hex.parkour.config.ParkourConfigLoader;
import hex.parkour.event.HexEventsBridge;
import hex.parkour.event.ParkourEventModule;
import hex.parkour.listener.ParkourPlayerListener;
import hex.parkour.persistence.ParkourTimesRepository;
import hex.parkour.persistence.SnapshotRepository;
import hex.parkour.placeholder.HexParkourPlaceholderExpansion;
import hex.parkour.service.BossBarService;
import hex.parkour.service.EconomyRewardService;
import hex.parkour.service.EffectService;
import hex.parkour.service.FinishRewardService;
import hex.parkour.service.ParkourItemService;
import hex.parkour.service.ParkourSessionService;
import hex.parkour.service.TerrainValidationService;
import hex.parkour.service.VisibilityService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class HexParkourPlugin extends JavaPlugin implements Listener {
    private ParkourConfig config;
    private ParkourSessionService sessions;
    private ParkourTimesRepository times;
    private ModuleRegistration registration;
    private HexParkourPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        SnapshotRepository snapshots = new SnapshotRepository(this);
        snapshots.initialize();
        times = new ParkourTimesRepository(this);
        times.initialize();
        ParkourItemService items = new ParkourItemService(this);
        BossBarService bossBars = new BossBarService();
        VisibilityService visibility = new VisibilityService(this);
        sessions = new ParkourSessionService(
                this,
                snapshots,
                times,
                items,
                bossBars,
                visibility,
                new TerrainValidationService(),
                new EffectService(),
                new EconomyRewardService(),
                new FinishRewardService(this, times)
        );
        reloadParkourConfig();

        HexEventsBridge bridge = HexEventsBridge.create(this);
        if (bridge == null) {
            getLogger().severe("HexEventsApi unavailable; disabling HexParkour.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        sessions.eventsBridge(bridge);
        registration = bridge.registerModule(new ParkourEventModule(sessions));
        getServer().getPluginManager().registerEvents(new ParkourPlayerListener(sessions, items), this);
        getServer().getPluginManager().registerEvents(this, this);
        HexParkourCommand command = new HexParkourCommand(() -> config, sessions, this::reloadParkourConfig);
        getCommand("hexparkour").setExecutor(command);
        getCommand("hexparkour").setTabCompleter(command);
        registerPlaceholderExpansion();
        sessions.startTicking();
        getLogger().info("HexParkour enabled. availability=" + (sessions.available() ? "AVAILABLE" : sessions.availabilityReason()));
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        if (registration != null) {
            registration.close();
            registration = null;
        }
        if (sessions != null) {
            sessions.shutdown();
            sessions = null;
        }
        times = null;
    }

    public void reloadParkourConfig() {
        reloadConfig();
        config = new ParkourConfigLoader(this).load();
        if (sessions != null) sessions.configure(config);
    }

    private void registerPlaceholderExpansion() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI not detected; HexParkour placeholders are disabled.");
            return;
        }
        this.placeholderExpansion = new HexParkourPlaceholderExpansion(this, sessions, times);
        if (placeholderExpansion.register()) {
            getLogger().info("Registered PlaceholderAPI expansion: hexparkour.");
        } else {
            getLogger().warning("Failed to register PlaceholderAPI expansion: hexparkour.");
        }
    }

    @EventHandler
    public void onDependencyDisable(PluginDisableEvent event) {
        if (!event.getPlugin().getName().equalsIgnoreCase("HexEvents")) return;
        getLogger().severe("HexEvents disabled while HexParkour is active; restoring all active Parkour players.");
        if (registration != null) {
            registration.close();
            registration = null;
        }
        if (sessions != null) sessions.shutdown();
    }
}
