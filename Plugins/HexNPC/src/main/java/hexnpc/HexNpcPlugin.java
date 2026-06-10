package hexnpc;

import com.github.retrooper.packetevents.event.PacketListenerCommon;
import hexnpc.action.ConsoleCommandHandler;
import hexnpc.action.MessageHandler;
import hexnpc.action.PlayerCommandHandler;
import hexnpc.command.HexNpcCommand;
import hexnpc.config.HexNpcConfig;
import hexnpc.config.HexNpcConfigLoader;
import hexnpc.integration.HexCoreBridge;
import hexnpc.listener.PlayerLifecycleListener;
import hexnpc.render.NoopNpcRenderer;
import hexnpc.render.NpcRenderer;
import hexnpc.render.packet.NpcClickPacketListener;
import hexnpc.render.packet.PacketEventsBootstrap;
import hexnpc.render.packet.PacketNpcRenderer;
import hexnpc.service.DialogueService;
import hexnpc.service.NpcActionRegistry;
import hexnpc.service.NpcInteractionService;
import hexnpc.service.NpcProximityService;
import hexnpc.service.NpcService;
import hexnpc.service.skin.SkinResolver;
import hexnpc.storage.YamlNpcStorage;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

public class HexNpcPlugin extends JavaPlugin {

    private final HexNpcConfigLoader configLoader = new HexNpcConfigLoader();
    private final AtomicReference<HexNpcConfig> configRef = new AtomicReference<>();

    // Built ONCE per lifecycle (onEnable -> onDisable). Survives reload.
    private NpcActionRegistry actionRegistry;
    private HexCoreBridge hexCoreBridge;
    private SkinResolver skinResolver;
    private PacketListenerCommon packetClickListener;

    // Rebuilt every reload.
    private YamlNpcStorage storage;
    private NpcRenderer renderer;
    private NpcService npcService;
    private DialogueService dialogueService;
    private NpcInteractionService interactionService;
    private NpcProximityService proximityService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureNpcsFile();

        // Built once: action registry survives reload so external plugins keep theirs.
        this.actionRegistry = new NpcActionRegistry();
        actionRegistry.register(new MessageHandler());
        actionRegistry.register(new ConsoleCommandHandler());
        actionRegistry.register(new PlayerCommandHandler());
        getServer().getServicesManager().register(
                NpcActionRegistry.class, actionRegistry, this, ServicePriority.Normal);

        this.hexCoreBridge = new HexCoreBridge(getLogger());
        this.skinResolver = new SkinResolver(getLogger());

        if (!initializeRuntime()) {
            getLogger().severe("Failed to initialize HexNPC. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!registerCommand()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Listeners registered once. They look up swappable services through this plugin.
        getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(this), this);
        registerPacketClickListenerOnce();

        getLogger().info("HexNPC enabled.");
    }

    @Override
    public void onDisable() {
        shutdownRuntime();
        if (packetClickListener != null) {
            PacketEventsBootstrap.unregisterListener(packetClickListener);
            packetClickListener = null;
        }
        if (actionRegistry != null) {
            getServer().getServicesManager().unregister(NpcActionRegistry.class, actionRegistry);
            actionRegistry = null;
        }
        if (skinResolver != null) {
            skinResolver.shutdown();
            skinResolver = null;
        }
        hexCoreBridge = null;
        getLogger().info("HexNPC disabled.");
    }

    public boolean reloadPluginRuntime() {
        shutdownRuntime();
        reloadConfig();
        return initializeRuntime();
    }

    public HexNpcConfig config() {
        return configRef.get();
    }

    public NpcService npcService() {
        return npcService;
    }

    public NpcRenderer renderer() {
        return renderer;
    }

    public NpcActionRegistry actionRegistry() {
        return actionRegistry;
    }

    public DialogueService dialogueService() {
        return dialogueService;
    }

    public NpcInteractionService interactionService() {
        return interactionService;
    }

    public NpcProximityService proximityService() {
        return proximityService;
    }

    public HexCoreBridge hexCoreBridge() {
        return hexCoreBridge;
    }

    public SkinResolver skinResolver() {
        return skinResolver;
    }

    private boolean initializeRuntime() {
        HexNpcConfig loaded = configLoader.load(getConfig());
        configRef.set(loaded);

        if (hexCoreBridge != null && hexCoreBridge.isAvailable()) {
            getLogger().info("HexCore detected — HexCoreBridge ready (read-only).");
        }

        this.storage = new YamlNpcStorage(new File(getDataFolder(), "npcs.yml"), getLogger());
        this.renderer = createRenderer(loaded);
        this.renderer.start();
        this.npcService = new NpcService(storage, renderer, configRef::get, getLogger());

        this.dialogueService = new DialogueService(this, configRef::get);
        this.interactionService = new NpcInteractionService(dialogueService, actionRegistry, configRef::get, getLogger());

        try {
            if (loaded.enabled()) {
                npcService.loadAndSpawnAll();
            } else {
                // Still load into memory so list/edit commands work; just don't render.
                npcService.loadOnly();
                getLogger().info("HexNPC: enabled=false — NPCs loaded but not rendered.");
            }
        } catch (Exception ex) {
            getLogger().severe("Failed to load NPCs: " + ex.getMessage());
            ex.printStackTrace();
            return false;
        }

        this.proximityService = new NpcProximityService(this, npcService, interactionService, configRef::get);
        proximityService.start();
        return true;
    }

    private NpcRenderer createRenderer(HexNpcConfig loaded) {
        if (!PacketEventsBootstrap.isAvailable()) {
            getLogger().warning("PacketEvents not detected. Falling back to no-op renderer; NPCs will be persisted but invisible.");
            return new NoopNpcRenderer(getLogger(), loaded.debug());
        }
        try {
            return new PacketNpcRenderer(this, configRef::get, getLogger());
        } catch (Throwable t) {
            getLogger().severe("Failed to initialize PacketNpcRenderer: " + t.getMessage());
            t.printStackTrace();
            return new NoopNpcRenderer(getLogger(), loaded.debug());
        }
    }

    private void registerPacketClickListenerOnce() {
        if (packetClickListener != null) {
            return;
        }
        if (!PacketEventsBootstrap.isAvailable()) {
            return;
        }
        try {
            NpcClickPacketListener listener = new NpcClickPacketListener(this);
            PacketEventsBootstrap.registerListener(listener);
            packetClickListener = listener;
        } catch (Throwable t) {
            getLogger().warning("Failed to register packet click listener: " + t.getMessage());
        }
    }

    private void shutdownRuntime() {
        if (proximityService != null) {
            proximityService.stop();
            proximityService = null;
        }
        if (dialogueService != null) {
            dialogueService.clearAll();
            dialogueService = null;
        }
        interactionService = null;
        if (npcService != null) {
            npcService.despawnAll();
            npcService = null;
        }
        if (renderer != null) {
            renderer.stop();
            renderer = null;
        }
        storage = null;
    }

    private boolean registerCommand() {
        var command = getCommand("hexnpc");
        if (command == null) {
            getLogger().severe("Command 'hexnpc' missing from plugin.yml.");
            return false;
        }
        HexNpcCommand executor = new HexNpcCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        return true;
    }

    private void ensureNpcsFile() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Failed to create plugin data folder.");
            return;
        }
        File npcs = new File(getDataFolder(), "npcs.yml");
        if (!npcs.exists()) {
            saveResource("npcs.yml", false);
        }
    }
}
