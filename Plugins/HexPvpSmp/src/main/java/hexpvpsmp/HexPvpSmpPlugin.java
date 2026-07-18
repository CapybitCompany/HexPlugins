package hexpvpsmp;

import hexpvpsmp.combat.CombatCommandListener;
import hexpvpsmp.combat.CombatListener;
import hexpvpsmp.combat.CombatLogListener;
import hexpvpsmp.combat.CombatTagService;
import hexpvpsmp.command.HexPvpCommand;
import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.config.HexPvpConfigLoader;
import hexpvpsmp.movement.SafezoneInfoListener;
import hexpvpsmp.movement.SafezoneMovementListener;
import hexpvpsmp.protection.ConfigRegionProtectionProvider;
import hexpvpsmp.protection.InteractionProtectionListener;
import hexpvpsmp.protection.ProtectionProvider;
import hexpvpsmp.protection.ProtectionService;
import hexpvpsmp.protection.PublicChestRegistry;
import hexpvpsmp.protection.SpawnProtectionListener;
import hexpvpsmp.redline.BarrierService;
import hexpvpsmp.ui.ActionBarService;
import hexpvpsmp.ui.MessageService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class HexPvpSmpPlugin extends JavaPlugin {

    private final HexPvpConfigLoader configLoader = new HexPvpConfigLoader();
    private final AtomicReference<HexPvpConfig> configRef = new AtomicReference<>();

    // Built once per plugin lifecycle.
    private CombatTagService combatTagService;
    private MessageService messageService;
    private PublicChestRegistry publicChestRegistry;
    private BarrierService barrierService;

    // Rebuilt on reload.
    private ProtectionService protectionService;
    private ActionBarService actionBarService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.messageService = new MessageService(getServer(), configRef::get);
        this.combatTagService = new CombatTagService(getServer(), configRef::get);
        this.publicChestRegistry = new PublicChestRegistry(configRef::get);
        this.barrierService = new BarrierService(this);

        if (!initializeRuntime()) {
            getLogger().severe("Failed to initialize HexPvpSmp. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!registerCommand()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Listeners registered once. They look up swappable services via getters.
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(new CombatCommandListener(this), this);
        getServer().getPluginManager().registerEvents(new CombatLogListener(this), this);
        getServer().getPluginManager().registerEvents(new SafezoneMovementListener(this), this);
        getServer().getPluginManager().registerEvents(new SafezoneInfoListener(this), this);
        getServer().getPluginManager().registerEvents(new SpawnProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new InteractionProtectionListener(this), this);

        getLogger().info("HexPvpSmp enabled.");
    }

    @Override
    public void onDisable() {
        shutdownRuntime();
        if (combatTagService != null) {
            combatTagService.clearAll();
            combatTagService = null;
        }
        messageService = null;
        publicChestRegistry = null;
        barrierService = null;
        getLogger().info("HexPvpSmp disabled.");
    }

    public boolean reloadPluginRuntime() {
        shutdownRuntime();
        reloadConfig();
        return initializeRuntime();
    }

    public HexPvpConfig config() {
        return configRef.get();
    }

    public CombatTagService combatTagService() {
        return combatTagService;
    }

    public MessageService messageService() {
        return messageService;
    }

    public ProtectionService protectionService() {
        return protectionService;
    }

    public PublicChestRegistry publicChestRegistry() {
        return publicChestRegistry;
    }

    public BarrierService barrierService() {
        return barrierService;
    }

    public ActionBarService actionBarService() {
        return actionBarService;
    }

    /** Whether verbose protection logging is active. */
    public boolean debug() {
        HexPvpConfig current = configRef.get();
        return current != null && current.debug();
    }

    public void debugLog(String message) {
        if (debug()) {
            getLogger().info("[debug] " + message);
        }
    }

    public void setRuntimeDebug(boolean enabled) {
        HexPvpConfig current = configRef.get();
        if (current == null) {
            return;
        }
        configRef.set(new HexPvpConfig(
                current.enabled(), enabled,
                current.combat(), current.safezones(),
                current.protection(), current.messages(), current.worlds()
        ));
    }

    private boolean initializeRuntime() {
        try {
            HexPvpConfig loaded = configLoader.load(getConfig(), getLogger());
            configRef.set(loaded);

            List<ProtectionProvider> providers = List.of(
                    new ConfigRegionProtectionProvider(configRef::get)
            );
            this.protectionService = new ProtectionService(providers);

            this.actionBarService = new ActionBarService(this, combatTagService, messageService, configRef::get);
            actionBarService.start();
            return true;
        } catch (Exception ex) {
            getLogger().severe("Initialization failure: " + ex.getMessage());
            ex.printStackTrace();
            return false;
        }
    }

    private void shutdownRuntime() {
        if (actionBarService != null) {
            actionBarService.stop();
            actionBarService = null;
        }
        protectionService = null;
    }

    private boolean registerCommand() {
        PluginCommand command = getCommand("hexpvp");
        if (command == null) {
            getLogger().severe("Command 'hexpvp' missing from plugin.yml.");
            return false;
        }
        HexPvpCommand executor = new HexPvpCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        return true;
    }
}
