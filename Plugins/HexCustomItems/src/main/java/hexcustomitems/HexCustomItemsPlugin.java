package hexcustomitems;

import hexcustomitems.command.HexCustomItemCommand;
import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.config.HexCustomItemsConfigLoader;
import hexcustomitems.listener.CooldownCleanupListener;
import hexcustomitems.listener.CustomItemsAnvilListener;
import hexcustomitems.listener.CustomItemsCraftingListener;
import hexcustomitems.listener.CustomItemsDamageListener;
import hexcustomitems.listener.CustomItemsDropListener;
import hexcustomitems.listener.CustomItemsInteractListener;
import hexcustomitems.listener.CustomItemsMenuListener;
import hexcustomitems.listener.CustomItemsMiningListener;
import hexcustomitems.listener.CustomItemsMobDropListener;
import hexcustomitems.listener.CustomItemsProjectileListener;
import hexcustomitems.listener.PlayerDataListener;
import hexcustomitems.region.RegionGuardFactory;
import hexcustomitems.region.RegionQuery;
import hexcustomitems.service.ActionExecutor;
import hexcustomitems.service.CombatIntegrationService;
import hexcustomitems.service.CooldownService;
import hexcustomitems.service.CooldownStore;
import hexcustomitems.service.CustomItemRegistryService;
import hexcustomitems.service.CustomItemUseService;
import hexcustomitems.service.GiveService;
import hexcustomitems.service.MessageService;
import hexcustomitems.service.PermissionRegistrar;
import hexcustomitems.service.PlayerDataService;
import hexcustomitems.service.SpecialItemActionService;
import hexcustomitems.service.UsePolicyService;
import hexcustomitems.ui.MenuService;
import hexcustomitems.util.PapiSupport;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicReference;

// Nicht final: MockBukkit erzeugt zur Testzeit eine Proxy-Subklasse.
public class HexCustomItemsPlugin extends JavaPlugin {

    private final AtomicReference<HexCustomItemsConfig> configRef = new AtomicReference<>();
    private HexCustomItemsConfigLoader configLoader;
    private CustomItemRegistryService registryService;
    private PermissionRegistrar permissionRegistrar;
    private CooldownService cooldownService;
    private CooldownStore cooldownStore;
    private MessageService messageService;
    private GiveService giveService;
    private PlayerDataService playerDataService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PapiSupport.init(this);

        this.configLoader = new HexCustomItemsConfigLoader(this);
        HexCustomItemsConfig config = configLoader.load();
        this.configRef.set(config);

        this.messageService = new MessageService(configRef::get);
        this.registryService = new CustomItemRegistryService(this, config);
        this.cooldownService = new CooldownService();
        this.cooldownStore = new CooldownStore(this, config.cooldowns().file());
        if (config.cooldowns().persist()) {
            cooldownService.load(cooldownStore.read());
        }
        this.playerDataService = new PlayerDataService(this);
        playerDataService.load();

        RegionQuery regionQuery = RegionGuardFactory.create(this);
        UsePolicyService policyService = new UsePolicyService(configRef::get, regionQuery);
        CombatIntegrationService combatIntegration = new CombatIntegrationService(this);
        SpecialItemActionService specialActions = new SpecialItemActionService(
                this, registryService, playerDataService, combatIntegration, messageService);
        ActionExecutor actionExecutor = new ActionExecutor(this, messageService, hexcustomitems.service.CommandDispatcher.BUKKIT, specialActions);
        CustomItemUseService useService = new CustomItemUseService(
                registryService, cooldownService, policyService, actionExecutor, messageService);

        this.giveService = new GiveService(configRef::get, registryService, messageService);
        MenuService menuService = new MenuService(this, registryService, configRef::get);

        this.permissionRegistrar = new PermissionRegistrar(this);
        permissionRegistrar.apply(config.items().values(), config.itemPermissionDefault());

        if (!registerMainCommand(menuService)) {
            return;
        }

        getServer().getPluginManager().registerEvents(new CustomItemsInteractListener(useService), this);
        getServer().getPluginManager().registerEvents(new CustomItemsDropListener(registryService, messageService), this);
        getServer().getPluginManager().registerEvents(new CustomItemsMenuListener(registryService, giveService, menuService, configRef::get), this);
        getServer().getPluginManager().registerEvents(new CooldownCleanupListener(cooldownService, configRef::get), this);
        getServer().getPluginManager().registerEvents(new PlayerDataListener(playerDataService), this);
        getServer().getPluginManager().registerEvents(new CustomItemsProjectileListener(specialActions), this);
        getServer().getPluginManager().registerEvents(new CustomItemsMiningListener(specialActions), this);
        getServer().getPluginManager().registerEvents(new CustomItemsDamageListener(specialActions), this);
        getServer().getPluginManager().registerEvents(new CustomItemsMobDropListener(configRef::get, registryService), this);
        getServer().getPluginManager().registerEvents(new CustomItemsCraftingListener(configRef::get, registryService), this);
        getServer().getPluginManager().registerEvents(new CustomItemsAnvilListener(registryService, messageService), this);

        getLogger().info("HexCustomItems uruchomiony.");
    }

    @Override
    public void onDisable() {
        if (configRef.get() != null && configRef.get().cooldowns().persist() && cooldownStore != null && cooldownService != null) {
            cooldownStore.write(cooldownService.snapshot());
        }
        if (playerDataService != null) {
            playerDataService.save();
        }
        if (permissionRegistrar != null) {
            permissionRegistrar.clear();
        }
        getLogger().info("HexCustomItems zatrzymany.");
    }

    private boolean registerMainCommand(MenuService menuService) {
        PluginCommand mainCommand = getCommand("hexcustomitem");
        if (mainCommand == null) {
            getLogger().severe("Brak komendy 'hexcustomitem' w plugin.yml. Wyłączam plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        HexCustomItemCommand executor = new HexCustomItemCommand(
                configRef::get,
                registryService,
                giveService,
                menuService,
                messageService,
                this::reloadHexCustomItemsConfiguration
        );
        mainCommand.setExecutor(executor);
        mainCommand.setTabCompleter(executor);
        return true;
    }

    private void reloadHexCustomItemsConfiguration() {
        reloadConfig();
        HexCustomItemsConfig updated = configLoader.load();
        configRef.set(updated);
        registryService.updateConfig(updated);
        permissionRegistrar.apply(updated.items().values(), updated.itemPermissionDefault());
        // Cooldown-Persistenz vollständig config-driven: Store an geänderte cooldowns.file anpassen.
        // Aktive (In-Memory-)Cooldowns bleiben im cooldownService erhalten.
        this.cooldownStore = new CooldownStore(this, updated.cooldowns().file());
    }

    // Test-Zugriff auf den internen Cooldown-Service (z.B. Reload-/Persistenz-Tests).
    CooldownService cooldownService() {
        return cooldownService;
    }
}
