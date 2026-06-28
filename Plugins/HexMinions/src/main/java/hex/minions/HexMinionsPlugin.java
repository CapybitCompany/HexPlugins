package hex.minions;

import hex.core.api.compat.MinecraftCompatibility;
import hex.core.api.HexApi;
import hex.collections.api.HexCollectionsApi;
import hex.minions.advancement.MinionAdvancementService;
import hex.minions.api.MinionsApi;
import hex.minions.command.MinionCommand;
import hex.minions.config.DefinitionLoader;
import hex.minions.config.Definitions;
import hex.minions.config.MinionsConfig;
import hex.minions.config.StorageChestRegistry;
import hex.minions.crafting.SpecialItemRegistry;
import hex.minions.customdrops.CustomResourceDropEngine;
import hex.minions.listener.SpecialCraftingListener;
import hex.minions.listener.MachineListener;
import hex.minions.listener.EnderChestExpansionListener;
import hex.minions.machine.MachineService;
import hex.minions.energy.CableService;
import hex.minions.listener.RadiationListener;
import hex.minions.database.MinionRepository;
import hex.minions.listener.MinionInteractionListener;
import hex.minions.listener.MinionMenuListener;
import hex.minions.listener.MinionWorldListener;
import hex.minions.listener.TownLifecycleListener;
import hex.minions.menu.MinionMenu;
import hex.minions.placeholder.MinionsPlaceholderExpansion;
import hex.minions.render.MinionRenderer;
import hex.minions.service.MinionItemFactory;
import hex.minions.service.MinionService;
import hex.minions.service.MinionsApiImpl;
import hex.towns.api.TownsApi;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class HexMinionsPlugin extends JavaPlugin {
    private HexApi hex;
    private MinionService service;
    private MinionRenderer renderer;
    private MachineService machineService;
    private CableService cableService;
    private CustomResourceDropEngine customResourceDropEngine;
    private MinionAdvancementService advancementService;
    private MinionsApi api;
    private MinionsPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        MinecraftCompatibility.logStartupCompatibility(this);
        saveDefaultConfig();
        saveResourceIfMissing("resources.yml");
        saveResourceIfMissing("minion-types.yml");
        saveResourceIfMissing("upgrades.yml");
        saveResourceIfMissing("appearance.yml");
        saveResourceIfMissing("menus.yml");
        saveResourceIfMissing("limits.yml");
        saveResourceIfMissing("storage-chests.yml");
        saveResourceIfMissing("special-items.yml");
        saveResourceIfMissing("machines.yml");
        saveResourceIfMissing("minion-advancements.yml");

        var hexReg = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (hexReg == null) {
            getLogger().severe("HexCore not found; disabling HexMinions.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.hex = hexReg.getProvider();

        var townsReg = Bukkit.getServicesManager().getRegistration(TownsApi.class);
        if (townsReg == null) {
            getLogger().severe("HexTowns API not found; disabling HexMinions.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        TownsApi towns = townsReg.getProvider();

        HexCollectionsApi collections = findCollectionsApi();
        if (collections == null) {
            getLogger().severe("HexCollections API not found; disabling HexMinions.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registerUiDefaults();

        MinionsConfig config = MinionsConfig.load(getConfig());
        Definitions definitions = new DefinitionLoader(this).load();
        MinionRepository repository = new MinionRepository(hex.db().db());
        StorageChestRegistry storageChests = StorageChestRegistry.load(this);
        SpecialItemRegistry specialItems = SpecialItemRegistry.load(this);
        MinionItemFactory itemFactory = new MinionItemFactory(this);
        storageChests.registerRecipes(this, itemFactory);
        specialItems.registerVanillaRecipes(itemFactory, storageChests, definitions);
        this.renderer = new MinionRenderer(this, definitions);
        this.service = new MinionService(this, hex, towns, collections, repository, renderer, itemFactory, config, definitions, storageChests, specialItems);
        this.machineService = new MachineService(this, hex, service);
        this.cableService = new CableService(this, hex, towns, service);
        this.cableService.attachMachines(machineService);
        this.machineService.attachCableService(cableService);
        this.customResourceDropEngine = new CustomResourceDropEngine(this, hex, towns, service);
        this.customResourceDropEngine.start();
        this.machineService.start();
        this.advancementService = new MinionAdvancementService(this, towns, collections, service);
        this.advancementService.reload();
        this.service.registerListener(advancementService);
        this.api = new MinionsApiImpl(service);

        Bukkit.getServicesManager().register(MinionsApi.class, api, this, ServicePriority.Normal);
        towns.dataNamespace(this, "minions", (townId, members) -> service.purgeTown(townId));
        registerPlaceholderExpansion();

        MinionMenu menu = new MinionMenu(hex, service, itemFactory);
        MinionCommand command = new MinionCommand(this, hex, service, itemFactory, menu, this::reloadRuntimeConfig);
        PluginCommand pluginCommand = getCommand("minion");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        getServer().getPluginManager().registerEvents(new MinionInteractionListener(this, hex, service, itemFactory, renderer, menu), this);
        getServer().getPluginManager().registerEvents(new MinionMenuListener(this, hex, service, menu), this);
        getServer().getPluginManager().registerEvents(new SpecialCraftingListener(this, hex, towns, service, menu), this);
        getServer().getPluginManager().registerEvents(new MachineListener(this, hex, towns, machineService), this);
        getServer().getPluginManager().registerEvents(cableService, this);
        getServer().getPluginManager().registerEvents(customResourceDropEngine, this);
        getServer().getPluginManager().registerEvents(new RadiationListener(this, service), this);
        getServer().getPluginManager().registerEvents(new TownLifecycleListener(service, machineService), this);
        getServer().getPluginManager().registerEvents(new MinionWorldListener(this, service, machineService), this);
        getServer().getPluginManager().registerEvents(new EnderChestExpansionListener(this, towns, service), this);
        getServer().getPluginManager().registerEvents(advancementService, this);

        hex.db().async(() -> {
            repository.ensureTables();
            var minions = repository.loadMinions();
            for (var minion : minions) {
                minion.replaceStorage(repository.loadStorage(minion.id()));
                minion.replaceAddonItems(repository.loadAddonItems(minion.id()));
            }
            return minions;
        }).thenAccept(minions -> Bukkit.getScheduler().runTask(this, () -> {
            service.load(minions);
            service.startTasks();
            if (advancementService != null) Bukkit.getOnlinePlayers().forEach(advancementService::evaluate);
            getLogger().info("HexMinions loaded minions=" + minions.size());
        })).exceptionally(ex -> {
            getLogger().severe("HexMinions DB init failed: " + rootMessage(ex));
            Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
            return null;
        });

        getLogger().info("HexMinions enabled");
    }

    @Override
    public void onDisable() {
        if (advancementService != null) advancementService.shutdown();
        if (machineService != null) machineService.shutdown();
        if (cableService != null) cableService.shutdown();
        if (customResourceDropEngine != null) customResourceDropEngine.shutdown();
        if (service != null) service.stopTasks();
        if (renderer != null) renderer.shutdown();
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        if (api != null) Bukkit.getServicesManager().unregister(MinionsApi.class, api);
        getLogger().info("HexMinions disabled");
    }

    private void registerPlaceholderExpansion() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI not found; skipping HexMinions placeholders.");
            return;
        }
        try {
            this.placeholderExpansion = new MinionsPlaceholderExpansion(this, api);
            if (placeholderExpansion.register()) {
                getLogger().info("Registered PlaceholderAPI expansion %hexminions_%.");
            } else {
                getLogger().warning("Could not register PlaceholderAPI expansion %hexminions_%.");
                this.placeholderExpansion = null;
            }
        } catch (Throwable throwable) {
            getLogger().warning("Could not register HexMinions placeholders: " + throwable.getMessage());
            this.placeholderExpansion = null;
        }
    }

    private void reloadRuntimeConfig() {
        reloadConfig();
        Definitions definitions = new DefinitionLoader(this).load();
        StorageChestRegistry storageChests = StorageChestRegistry.load(this);
        SpecialItemRegistry specialItems = SpecialItemRegistry.load(this);
        MinionItemFactory factory = new MinionItemFactory(this);
        storageChests.registerRecipes(this, factory);
        specialItems.registerVanillaRecipes(factory, storageChests, definitions);
        renderer.reload(definitions);
        service.reload(MinionsConfig.load(getConfig()), definitions, storageChests, specialItems);
        if (customResourceDropEngine != null) customResourceDropEngine.reload();
        if (advancementService != null) advancementService.reload();
    }

    private void saveResourceIfMissing(String name) {
        if (!new java.io.File(getDataFolder(), name).exists()) saveResource(name, false);
    }

    private void registerUiDefaults() {
        try {
            hex.ui().registerDefaults("minions", Map.ofEntries(
                    Map.entry("help", "<gray>/minion give, list, wiki, pickup, move, select-index, action, reload</gray>"),
                    Map.entry("error.player-only", "<red>Ta komenda jest tylko dla gracza.</red>"),
                    Map.entry("error.no-permission", "<red>Brak uprawnien.</red>"),
                    Map.entry("error.player-not-found", "<red>Nie znaleziono gracza <white><player></white>.</red>"),
                    Map.entry("error.no-town", "<red>Nie nalezysz do miasta.</red>"),
                    Map.entry("error.not-member", "<red>Nie jestes czlonkiem miasta tego miniona.</red>"),
                    Map.entry("error.not-found", "<red>Nie znaleziono miniona.</red>"),
                    Map.entry("error.bad-id", "<red>Niepoprawne UUID miniona.</red>"),
                    Map.entry("error.unknown-type", "<red>Nieznany typ miniona.</red>"),
                    Map.entry("error.not-in-own-town", "<red>Miniona mozna postawic tylko w swoim miescie.</red>"),
                    Map.entry("error.limit-reached", "<red>Miasto osiagnelo limit minionow: <white><limit></white>.</red>"),
                    Map.entry("error.db", "<red>Blad bazy danych: <white><error></white></red>"),
                    Map.entry("error.storage-empty", "<gray>Storage miniona jest pusty.</gray>"),
                    Map.entry("error.invalid-menu-item", "<red>Tego itemu nie mozna wlozyc w ten slot miniona.</red>"),
                    Map.entry("pickup.error.addons-not-empty", "<red>Najpierw wyjmij specjalne dodatki z miniona.</red>"),
                    Map.entry("storage-chest.error.no-space-left", "<red>Po lewej stronie miniona nie ma miejsca na skrzynke storage.</red>"),
                    Map.entry("storage-chest.error.break-linked", "<red>Tej skrzynki nie mozna zniszczyc recznie. Najpierw obsluz ja z menu miniona.</red>"),
                    Map.entry("place.success", "<green>Postawiono miniona <white><id></white>.</green>"),
                    Map.entry("pickup.success", "<green>Podniesiono miniona <white><id></white>.</green>"),
                    Map.entry("collect.success", "<green>Odebrano surowce z miniona.</green>"),
                    Map.entry("upgrade.success", "<green>Ulepszono miniona do tier <white><tier></white>.</green>"),
                    Map.entry("upgrade.missing-requirements", "<red>Nie spelniasz wymagan ulepszenia.</red>"),
                    Map.entry("storage-chest.place.success", "<green>Podlaczono skrzynke storage do miniona.</green>"),
                    Map.entry("storage-chest.install.success", "<green>Utworzono i podlaczono skrzynke storage po lewej stronie miniona.</green>"),
                    Map.entry("storage-chest.uninstall.success", "<green>Odlaczono skrzynke storage. Jej zawartosc wypadla obok miniona.</green>"),
                    Map.entry("storage-chest.error.not-found", "<gray>Ten minion nie ma podlaczonej skrzynki storage.</gray>"),
                    Map.entry("storage-chest.error.special-required", "<red>Obok miniona mozna postawic tylko skonfigurowany Minion Storage.</red>"),
                    Map.entry("storage-chest.error.already-has", "<red>Ten minion ma juz skrzynke storage.</red>"),
                    Map.entry("storage-chest.error.next-to-chest", "<red>Nie mozesz postawic Minion Storage obok innej skrzynki.</red>"),
                    Map.entry("special-crafting.error.no-town", "<red>Ten crafting wymaga miasta.</red>"),
                    Map.entry("special-crafting.error.locked", "<red>Nie spelniasz wymagan tej receptury.</red>"),
                    Map.entry("special-crafting.error.no-match", "<red>Nie wykryto poprawnej receptury.</red>"),
                    Map.entry("special-crafting.error.place-town", "<red>Ten blok mozesz postawic tylko na terenie swojego miasta.</red>"),
                    Map.entry("special-crafting.error.not-placeable", "<red>Tego specjalnego itemu nie mozna polozyc jako zwyklego bloku.</red>"),
                    Map.entry("move.success", "<green>Przeniesiono miniona <white><id></white>.</green>"),
                    Map.entry("move.error.disabled", "<red>Przenoszenie minionow jest wylaczone.</red>"),
                    Map.entry("move.error.not-same-town", "<red>Miniona mozna przeniesc tylko w obrebie tego samego miasta.</red>"),
                    Map.entry("move.error.location-invalid", "<red>Ta lokalizacja nie nadaje sie dla miniona.</red>"),
                    Map.entry("move.error.location-occupied", "<red>Ta lokalizacja jest zajeta albo zbyt blisko innego miniona.</red>"),
                    Map.entry("give.success", "<green>Dano <white><amount>x</white> miniona <yellow><type></yellow> graczowi <aqua><player></aqua>.</green>"),
                    Map.entry("list.header", "<gold>Miniony miasta:</gold> <white><count>/<limit></white>"),
                    Map.entry("list.line", "<gray>- <white><id></white> <yellow><name></yellow> T<tier> @ <location></gray>"),
                    Map.entry("wiki.open", "<green>Otwieram wiki minionów.</green>"),
                    Map.entry("wiki.test-copy.success", "<green>Skopiowano item z wiki do ekwipunku testowego.</green>"),
                    Map.entry("reload.success", "<green>Przeladowano HexMinions.</green>"),
                    Map.entry("ok", "<green>OK</green>")
            ));
        } catch (Throwable t) {
            getLogger().warning("Could not register UI defaults: " + t.getMessage());
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable t = throwable;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private HexCollectionsApi findCollectionsApi() {
        var registration = Bukkit.getServicesManager().getRegistration(HexCollectionsApi.class);
        return registration == null ? null : registration.getProvider();
    }
}


