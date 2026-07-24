package hexcustomitems;

import hexcustomitems.command.HexCustomItemsCommand;
import hexcustomitems.command.LegacyGiveCommand;
import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.config.HexCustomItemsConfigLoader;
import hexcustomitems.listener.CooldownCleanupListener;
import hexcustomitems.listener.CustomItemsDropListener;
import hexcustomitems.listener.CustomItemsInteractListener;
import hexcustomitems.listener.CustomItemsMenuListener;
import hexcustomitems.region.RegionGuardFactory;
import hexcustomitems.region.RegionQuery;
import hexcustomitems.service.ActionExecutor;
import hexcustomitems.service.CooldownService;
import hexcustomitems.service.CooldownStore;
import hexcustomitems.service.CustomItemRegistryService;
import hexcustomitems.service.CustomItemUseService;
import hexcustomitems.service.GiveService;
import hexcustomitems.service.MessageService;
import hexcustomitems.service.PermissionRegistrar;
import hexcustomitems.service.RecipeService;
import hexcustomitems.service.UsePolicyService;
import hexcustomitems.ui.MenuService;
import hexcustomitems.util.PapiSupport;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

// Nicht final: MockBukkit erzeugt zur Testzeit eine Proxy-Subklasse.
public class HexCustomItemsPlugin extends JavaPlugin {

    private final AtomicReference<HexCustomItemsConfig> configRef = new AtomicReference<>();
    private HexCustomItemsConfigLoader configLoader;
    private CustomItemRegistryService registryService;
    private RecipeService recipeService;
    private PermissionRegistrar permissionRegistrar;
    private CooldownService cooldownService;
    private CooldownStore cooldownStore;
    private MessageService messageService;
    private GiveService giveService;
    private final Set<String> boundLegacyCommands = new HashSet<>();

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

        RegionQuery regionQuery = RegionGuardFactory.create(this);
        UsePolicyService policyService = new UsePolicyService(configRef::get, regionQuery);
        ActionExecutor actionExecutor = new ActionExecutor(this, messageService);
        CustomItemUseService useService = new CustomItemUseService(
                registryService, cooldownService, policyService, actionExecutor, messageService);

        this.giveService = new GiveService(configRef::get, registryService, messageService);
        MenuService menuService = new MenuService(this, registryService, configRef::get);

        this.permissionRegistrar = new PermissionRegistrar(this);
        permissionRegistrar.apply(config.items().values(), config.itemPermissionDefault());

        this.recipeService = new RecipeService(this, registryService);
        recipeService.register(config);

        if (!registerMainCommand(menuService)) {
            return;
        }
        bindLegacyCommands(config);

        getServer().getPluginManager().registerEvents(new CustomItemsInteractListener(useService), this);
        getServer().getPluginManager().registerEvents(new CustomItemsDropListener(registryService, messageService), this);
        getServer().getPluginManager().registerEvents(new CustomItemsMenuListener(registryService, giveService, menuService, configRef::get), this);
        getServer().getPluginManager().registerEvents(new CooldownCleanupListener(cooldownService, configRef::get), this);

        getLogger().info("HexCustomItems uruchomiony.");
    }

    @Override
    public void onDisable() {
        if (configRef.get() != null && configRef.get().cooldowns().persist() && cooldownStore != null && cooldownService != null) {
            cooldownStore.write(cooldownService.snapshot());
        }
        if (recipeService != null) {
            recipeService.removeAll();
        }
        if (permissionRegistrar != null) {
            permissionRegistrar.clear();
        }
        getLogger().info("HexCustomItems zatrzymany.");
    }

    private boolean registerMainCommand(MenuService menuService) {
        PluginCommand mainCommand = getCommand("hexcustomitems");
        if (mainCommand == null) {
            getLogger().severe("Brak komendy 'hexcustomitems' w plugin.yml. Wyłączam plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        HexCustomItemsCommand executor = new HexCustomItemsCommand(
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

    /**
     * Bindet die in der Config definierten Legacy-Give-Kommandos an ihre statischen plugin.yml-Slots.
     * Beim Reload werden zuvor gebundene, jetzt entfernte Commands wieder auf den Plugin-Default
     * zurückgesetzt (kein Executor), damit sie keine Items mehr vergeben. Nur statische Slots,
     * kein Reflection-Registrieren.
     */
    private void bindLegacyCommands(HexCustomItemsConfig config) {
        Map<String, String> bindings = config.legacyCommandBindings();

        for (String previous : boundLegacyCommands) {
            if (!bindings.containsKey(previous)) {
                PluginCommand command = getCommand(previous);
                if (command != null) {
                    command.setExecutor(null); // zurück auf Plugin-Default -> inert, vergibt keine Items mehr
                }
            }
        }
        boundLegacyCommands.clear();

        for (Map.Entry<String, String> binding : bindings.entrySet()) {
            PluginCommand command = getCommand(binding.getKey());
            if (command == null) {
                getLogger().warning("Legacy-command '" + binding.getKey()
                        + "' z config nie istnieje w plugin.yml - pomijam. Dodaj ją do plugin.yml, aby działała.");
                continue;
            }
            command.setExecutor(new LegacyGiveCommand(configRef::get, giveService, messageService, binding.getValue()));
            boundLegacyCommands.add(binding.getKey());
        }
    }

    private void reloadHexCustomItemsConfiguration() {
        reloadConfig();
        HexCustomItemsConfig updated = configLoader.load();
        configRef.set(updated);
        registryService.updateConfig(updated);
        permissionRegistrar.apply(updated.items().values(), updated.itemPermissionDefault());
        recipeService.register(updated);
        bindLegacyCommands(updated);
        // Cooldown-Persistenz vollständig config-driven: Store an geänderte cooldowns.file anpassen.
        // Aktive (In-Memory-)Cooldowns bleiben im cooldownService erhalten.
        this.cooldownStore = new CooldownStore(this, updated.cooldowns().file());
    }

    // Test-Zugriff auf den internen Cooldown-Service (z.B. Reload-/Persistenz-Tests).
    CooldownService cooldownService() {
        return cooldownService;
    }
}
