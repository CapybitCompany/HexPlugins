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
import hexpvpsmp.protection.NativeSpawnProtectionManager;
import hexpvpsmp.protection.ProtectionProvider;
import hexpvpsmp.protection.ProtectionService;
import hexpvpsmp.protection.PublicChestRegistry;
import hexpvpsmp.protection.SpawnProtectionListener;
import hexpvpsmp.protection.WorldDiagnostics;
import hexpvpsmp.redline.BarrierService;
import hexpvpsmp.ui.ActionBarService;
import hexpvpsmp.ui.MessageService;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
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
    private NativeSpawnProtectionManager nativeSpawnProtection;

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
        this.nativeSpawnProtection = new NativeSpawnProtectionManager(getServer(), getLogger());

        if (!initializeRuntime()) {
            // Init failed before native spawn protection was touched, so the
            // server's native radius still guards spawn: no unprotected state.
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

        // Only now that this plugin's own protection is fully live do we take over
        // the native spawn radius. Ordering matters: if anything above failed we
        // returned early and the native radius still protects spawn.
        runWorldDiagnostics();
        applyNativeSpawnProtection();

        getLogger().info("HexPvpSmp v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        // Hand the native spawn radius back first, so the server is never left
        // both without this plugin's protection and without native protection.
        if (nativeSpawnProtection != null) {
            nativeSpawnProtection.restore();
            nativeSpawnProtection = null;
        }
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
        boolean ok = initializeRuntime();
        if (ok) {
            runWorldDiagnostics();
            applyNativeSpawnProtection();
        } else if (nativeSpawnProtection != null) {
            // Reload failed: this plugin's protection is down. Re-enable the
            // native spawn radius so spawn is not left completely unprotected.
            nativeSpawnProtection.restore();
        }
        return ok;
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

    public NativeSpawnProtectionManager nativeSpawnProtection() {
        return nativeSpawnProtection;
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

    /** Applies the configured native-spawn-protection policy for the current config. */
    private void applyNativeSpawnProtection() {
        HexPvpConfig current = configRef.get();
        if (nativeSpawnProtection == null || current == null) {
            return;
        }
        nativeSpawnProtection.apply(current.protection().disableNativeSpawnProtection());
    }

    /**
     * Warns (once per enable/reload) about any enabled configured world whose
     * name is not actually loaded on this server — the classic "config says
     * 'world' but the real world is named differently" trap. Reads world names
     * only; never loads a chunk.
     */
    private void runWorldDiagnostics() {
        HexPvpConfig current = configRef.get();
        if (current == null) {
            return;
        }
        List<String> loaded = new ArrayList<>();
        for (World world : getServer().getWorlds()) {
            loaded.add(world.getName());
        }
        for (String warning : WorldDiagnostics.findConfiguredWorldsNotLoaded(current.worlds(), loaded)) {
            getLogger().warning(warning);
        }
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
