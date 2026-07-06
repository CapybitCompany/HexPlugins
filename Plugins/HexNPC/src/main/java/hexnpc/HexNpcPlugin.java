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
import hexnpc.service.NpcLookAtService;
import hexnpc.service.NpcProximityService;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcSkin;
import hexnpc.service.NpcService;
import hexnpc.service.skin.MineSkinClient;
import hexnpc.service.skin.SkinResolver;
import hexnpc.service.skin.SkinSourceResolver;
import hexnpc.shop.ShopRegistry;
import hexnpc.shop.ShopService;
import hexnpc.shop.action.ShopActionHandler;
import hexnpc.shop.economy.EconomyBridge;
import hexnpc.shop.gui.ShopInventoryListener;
import hexnpc.storage.YamlNpcStorage;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class HexNpcPlugin extends JavaPlugin {

    private final HexNpcConfigLoader configLoader = new HexNpcConfigLoader();
    private final AtomicReference<HexNpcConfig> configRef = new AtomicReference<>();

    // Built ONCE per lifecycle (onEnable -> onDisable). Survives reload.
    private NpcActionRegistry actionRegistry;
    private HexCoreBridge hexCoreBridge;
    private SkinResolver skinResolver;
    private HttpClient httpClient;
    private PacketListenerCommon packetClickListener;
    private EconomyBridge economyBridge;
    private ShopRegistry shopRegistry;
    private ShopService shopService;
    private ShopInventoryListener shopInventoryListener;

    // Rebuilt every reload.
    private YamlNpcStorage storage;
    private NpcRenderer renderer;
    private NpcService npcService;
    private SkinSourceResolver skinSourceResolver;
    private DialogueService dialogueService;
    private NpcInteractionService interactionService;
    private NpcProximityService proximityService;
    private NpcLookAtService lookAtService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureNpcsFile();
        ensureShopsFile();

        // Built once: action registry survives reload so external plugins keep theirs.
        this.actionRegistry = new NpcActionRegistry();
        actionRegistry.register(new MessageHandler());
        actionRegistry.register(new ConsoleCommandHandler());
        actionRegistry.register(new PlayerCommandHandler());
        actionRegistry.register(new ShopActionHandler(this::shopService));
        getServer().getServicesManager().register(
                NpcActionRegistry.class, actionRegistry, this, ServicePriority.Normal);

        this.hexCoreBridge = new HexCoreBridge(getLogger());
        // Ein HttpClient fuer alle Skin-Aufloesungen (Mojang + MineSkin), einmal gebaut.
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.skinResolver = new SkinResolver(getLogger(), httpClient);
        this.economyBridge = new EconomyBridge(getLogger());
        this.shopRegistry = new ShopRegistry(getLogger());
        // Build ShopService once. configRef changes are picked up via supplier.
        this.shopService = new ShopService(this, shopRegistry, economyBridge,
                () -> {
                    HexNpcConfig c = configRef.get();
                    return c == null ? null : c.shops();
                },
                getLogger());

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
        this.shopInventoryListener = new ShopInventoryListener(shopService);
        getServer().getPluginManager().registerEvents(shopInventoryListener, this);
        registerPacketClickListenerOnce();

        getLogger().info("HexNPC enabled.");
    }

    @Override
    public void onDisable() {
        shutdownRuntime();
        if (shopInventoryListener != null) {
            HandlerList.unregisterAll(shopInventoryListener);
            shopInventoryListener = null;
        }
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
        httpClient = null;
        if (economyBridge != null) {
            economyBridge.shutdown();
            economyBridge = null;
        }
        shopService = null;
        shopRegistry = null;
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

    public NpcLookAtService lookAtService() {
        return lookAtService;
    }

    public HexCoreBridge hexCoreBridge() {
        return hexCoreBridge;
    }

    public SkinResolver skinResolver() {
        return skinResolver;
    }

    public SkinSourceResolver skinSourceResolver() {
        return skinSourceResolver;
    }

    public ShopRegistry shopRegistry() {
        return shopRegistry;
    }

    public ShopService shopService() {
        return shopService;
    }

    public EconomyBridge economyBridge() {
        return economyBridge;
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
        this.skinSourceResolver = buildSkinSourceResolver(loaded);

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

        // Skins mit einer Quelle (url/mineskin-uuid/name) aber ohne Textures einmalig
        // aufloesen, cachen und persistieren — damit nicht jeder Spawn/Restart die API belastet.
        warmSkins();

        this.proximityService = new NpcProximityService(this, npcService, interactionService, configRef::get);
        proximityService.start();

        // Look-At-Tracking: dreht NPCs packet-seitig zu nahen Spielern (unabhaengig von
        // Dialogue/Proximity-Triggern). Nutzt denselben Renderer, veraendert keine Location.
        this.lookAtService = new NpcLookAtService(this, npcService, renderer, configRef::get);
        lookAtService.start();

        reloadShopCatalog();
        return true;
    }

    private SkinSourceResolver buildSkinSourceResolver(HexNpcConfig loaded) {
        HexNpcConfig.Skins.MineSkin ms = loaded.skins().mineskin();
        MineSkinClient client = null;
        if (ms.enabled()) {
            client = new MineSkinClient(ms, getLogger(),
                    MineSkinClient.defaultHttp(httpClient, ms.requestTimeoutSeconds()));
        }
        return new SkinSourceResolver(skinResolver, client, ms.enabled(), getLogger());
    }

    /**
     * Loest fuer alle NPCs mit aufloesbarer, aber noch nicht signierter Skin-Quelle die
     * Textures asynchron auf und wendet das Ergebnis auf dem Main-Thread an (persistiert
     * value/signature). Fehler behalten den alten/Default-Skin — nie blockierend.
     */
    private void warmSkins() {
        if (npcService == null || skinSourceResolver == null) {
            return;
        }
        for (NpcDefinition def : List.copyOf(npcService.list())) {
            NpcSkin skin = def.skin();
            if (!skinSourceResolver.needsResolution(skin)) {
                continue;
            }
            NpcId id = def.id();
            skinSourceResolver.resolve(skin).whenComplete((resolved, ex) -> {
                if (resolved == null || !resolved.hasTexture()) {
                    return; // Fallback: alten/Default-Skin behalten (bereits geloggt).
                }
                getServer().getScheduler().runTask(this, () -> {
                    try {
                        // Nur anwenden, wenn der NPC weiterhin existiert und noch keine
                        // Textures hat (kein Ueberschreiben eines zwischenzeitlich manuell
                        // gesetzten Skins).
                        Optional<NpcDefinition> current = npcService.find(id);
                        if (current.isPresent() && !current.get().skin().hasTexture()) {
                            npcService.setSkin(id, resolved);
                        }
                    } catch (Exception failure) {
                        getLogger().warning("HexNPC: failed to apply resolved skin for "
                                + id + ": " + failure.getMessage());
                    }
                });
            });
        }
    }

    /** Loads (or reloads) the shop catalog from disk. */
    public int reloadShopCatalog() {
        if (shopRegistry == null) {
            return 0;
        }
        HexNpcConfig cfg = configRef.get();
        var shopConfig = cfg == null ? null : cfg.shops();
        try {
            return shopRegistry.reload(new File(getDataFolder(), "shops.yml"), shopConfig);
        } catch (IOException ex) {
            getLogger().warning("HexNPC: failed to reload shops.yml: " + ex.getMessage());
            return 0;
        }
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
        if (lookAtService != null) {
            lookAtService.stop();
            lookAtService = null;
        }
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
        skinSourceResolver = null;
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

    private void ensureShopsFile() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            return;
        }
        File shops = new File(getDataFolder(), "shops.yml");
        if (!shops.exists()) {
            saveResource("shops.yml", false);
        }
    }
}
