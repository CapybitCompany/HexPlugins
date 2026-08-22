package hex.minions;

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
import hex.minions.diagnostics.ProjectDiagnosticsService;
import hex.minions.listener.SpecialCraftingListener;
import hex.minions.listener.BioFuelListener;
import hex.minions.listener.MachineListener;
import hex.minions.listener.EnderChestExpansionListener;
import hex.minions.machine.MachineService;
import hex.minions.energy.CableService;
import hex.minions.listener.RadiationListener;
import hex.minions.database.MinionRepository;
import hex.minions.listener.MinionInteractionListener;
import hex.minions.listener.MinionMenuListener;
import hex.minions.listener.MinionWorldListener;
import hex.minions.listener.MusketListener;
import hex.minions.menu.MinionMenu;
import hex.minions.placeholder.MinionsPlaceholderExpansion;
import hex.minions.render.MinionRenderer;
import hex.minions.robot.MiningRobotManager;
import hex.minions.service.MinionItemFactory;
import hex.minions.service.MinionService;
import hex.minions.service.MinionsApiImpl;
import hex.towns.api.TownsApi;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

public final class HexMinionsPlugin extends JavaPlugin {
    private HexApi hex;
    private MinionService service;
    private MinionRenderer renderer;
    private MachineService machineService;
    private CableService cableService;
    private CustomResourceDropEngine customResourceDropEngine;
    private MinionAdvancementService advancementService;
    private MusketListener musketListener;
    private MiningRobotManager robotManager;
    private MinionsApi api;
    private MinionsPlaceholderExpansion placeholderExpansion;
    private ProjectDiagnosticsService diagnosticsService;

    @Override
    public void onEnable() {
        getLogger().info("Running on " + Bukkit.getName()
                + " " + Bukkit.getBukkitVersion()
                + " (Minecraft " + Bukkit.getMinecraftVersion() + ")");
        saveDefaultConfig();
        saveResourceIfMissing("resources.yml");
        saveResourceIfMissing("minion-types.yml");
        saveResourceIfMissing("appearance.yml");
        saveResourceIfMissing("menus.yml");
        saveResourceIfMissing("limits.yml");
        saveResourceIfMissing("storage-chests.yml");
        saveResourceIfMissing("special-items.yml");
        saveResourceIfMissing("machines.yml");
        saveResourceIfMissing("minion-advancements.yml");
        saveResourceIfMissing("robots.yml");
        migrateCoalMinionSpeedX3();
        migrateMinionTierSpeedCurve();
        migrateDynamicUpgradeCostPercents();
        migrateCompressionUnlockTierOne();

        var hexReg = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (hexReg == null) {
            getLogger().severe("HexCore not found; disabling HexMinions.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.hex = hexReg.getProvider();

        if (!ensureDatabaseAvailable()) {
            return;
        }

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
        this.diagnosticsService = new ProjectDiagnosticsService(this, service);
        if (getConfig().getBoolean("diagnostics.validate-on-startup", true)) diagnosticsService.validateAndLog();
        if (getConfig().getBoolean("diagnostics.generate-balance-report-on-startup", true)) {
            try {
                var report = diagnosticsService.generateBalanceReport();
                getLogger().info("Wygenerowano raport balansu HexMinions: " + report.toAbsolutePath());
            } catch (Exception ex) {
                getLogger().warning("Nie udało się wygenerować raportu balansu: " + rootMessage(ex));
            }
        }
        this.musketListener = new MusketListener(this, service);
        this.machineService = new MachineService(this, hex, service, towns);
        this.cableService = new CableService(this, hex, towns, service);
        this.cableService.attachMachines(machineService);
        this.machineService.attachCableService(cableService);
        this.customResourceDropEngine = new CustomResourceDropEngine(this, hex, towns, service);
        this.robotManager = new MiningRobotManager(this, hex, towns, service);
        this.robotManager.load();
        this.robotManager.start();
        this.customResourceDropEngine.start();
        this.machineService.start();
        this.advancementService = new MinionAdvancementService(this, towns, collections, service);
        this.advancementService.reload();
        this.service.registerListener(advancementService);
        MinionMenu menu = new MinionMenu(hex, service, itemFactory);
        this.api = new MinionsApiImpl(service, machineService, menu, advancementService);

        Bukkit.getServicesManager().register(MinionsApi.class, api, this, ServicePriority.Normal);
        // Keep lifecycle parts independent so one subsystem failure is retryable without
        // turning the whole HexMinions cleanup into a single fail-fast black box.
        towns.dataNamespaceV2(this, "minions", service::purgeTown);
        towns.dataNamespaceV2(this, "machines", ctx -> machineService.purgeTownAsync(ctx.townUuid(), ctx.worldName(), ctx.chunks()));
        towns.dataNamespaceV2(this, "cables", ctx -> cableService.purgeTownAsync(ctx.townUuid(), ctx.worldName(), ctx.chunks()));
        towns.dataNamespaceV2(this, "robot", ctx -> robotManager.purgeTown(ctx.townUuid(), ctx.chunks()));
        registerPlaceholderExpansion();

        MinionCommand command = new MinionCommand(this, hex, service, itemFactory, menu, robotManager, diagnosticsService, this::reloadRuntimeConfig);
        PluginCommand pluginCommand = getCommand("minion");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        MachineListener machineListener = new MachineListener(this, hex, towns, machineService, menu);
        getServer().getPluginManager().registerEvents(new MinionInteractionListener(this, hex, service, itemFactory, renderer, menu), this);
        getServer().getPluginManager().registerEvents(new MinionMenuListener(this, hex, service, menu), this);
        getServer().getPluginManager().registerEvents(new RadiationListener(this, hex, service), this);
        // MachineListener is registered before SpecialCraftingListener so an allowed block break can
        // remove runtime/visual state while the special-block PDC still exists. Placement clicks are
        // explicitly ignored by MachineListener when the held item is placeable.
        getServer().getPluginManager().registerEvents(machineListener, this);
        getServer().getPluginManager().registerEvents(new SpecialCraftingListener(this, hex, towns, service, menu, machineService, machineListener), this);
        getServer().getPluginManager().registerEvents(cableService, this);
        getServer().getPluginManager().registerEvents(customResourceDropEngine, this);
        getServer().getPluginManager().registerEvents(new MinionWorldListener(this, service, machineService), this);
        getServer().getPluginManager().registerEvents(new EnderChestExpansionListener(this, towns, service), this);
        getServer().getPluginManager().registerEvents(new BioFuelListener(this, service), this);
        getServer().getPluginManager().registerEvents(robotManager, this);
        getServer().getPluginManager().registerEvents(musketListener, this);
        getServer().getPluginManager().registerEvents(advancementService, this);

        hex.db().async(() -> {
            repository.ensureTables();
            var minions = repository.loadMinions();
            for (var minion : minions) {
                minion.replaceStorage(repository.loadStorage(minion.id()));
                minion.replaceAddonItems(repository.loadAddonItems(minion.id()));
                minion.replaceDeterministicDropProgress(repository.loadDeterministicDropProgress(minion.id()));
            }
            return minions;
        }).thenAccept(minions -> Bukkit.getScheduler().runTask(this, () -> {
            service.load(minions);
            service.startTasks();
            if (advancementService != null) Bukkit.getOnlinePlayers().forEach(advancementService::evaluate);
            getLogger().info("HexMinions loaded minions=" + minions.size());
        })).exceptionally(ex -> {
            getLogger().severe("HexMinions database startup failed: " + rootMessage(ex));
            Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
            return null;
        });

        getLogger().info("HexMinions enabled");
    }

    private void migrateCoalMinionSpeedX3() {
        File file = new File(getDataFolder(), "minion-types.yml");
        if (!file.isFile()) return;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            double[] legacy = {720D, 684D, 630D, 576D, 522D, 450D, 360D};
            double[] faster = {240D, 228D, 210D, 192D, 174D, 150D, 120D};
            boolean legacyProfile = true;
            for (int tier = 1; tier <= 7; tier++) {
                String path = "minion-types.coal.tiers." + tier + ".action-time-seconds";
                if (!yaml.contains(path) || Math.abs(yaml.getDouble(path) - legacy[tier - 1]) > 0.0001D) {
                    legacyProfile = false;
                    break;
                }
            }
            if (!legacyProfile) return;
            for (int tier = 1; tier <= 7; tier++) {
                yaml.set("minion-types.coal.tiers." + tier + ".action-time-seconds", faster[tier - 1]);
            }
            yaml.save(file);
            getLogger().info("Migrated Coal Minion production speed x3 (T1-T7). Existing custom timings were left untouched.");
        } catch (Exception ex) {
            getLogger().warning("Could not migrate Coal Minion speed: " + ex.getMessage());
        }
    }

    private void migrateMinionTierSpeedCurve() {
        File file = new File(getDataFolder(), "minion-types.yml");
        if (!file.isFile()) return;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            var root = yaml.getConfigurationSection("minion-types");
            if (root == null) return;

            // Previous global curve: T1..T7 = 2.00x, 1.90x, 1.75x, 1.60x, 1.45x, 1.25x, 1.00x T7.
            // New curve intentionally makes early tiers slower while preserving every type's T7 exactly.
            double[] oldCurve = {2.00D, 1.90D, 1.75D, 1.60D, 1.45D, 1.25D, 1.00D};
            double[] newCurve = {3.00D, 2.60D, 2.20D, 1.80D, 1.50D, 1.20D, 1.00D};
            int migrated = 0;
            int alreadyNew = 0;
            int custom = 0;

            for (String minionId : root.getKeys(false)) {
                String base = "minion-types." + minionId + ".tiers.";
                String tier7Path = base + "7.action-time-seconds";
                if (!yaml.contains(tier7Path)) continue;
                double tier7 = yaml.getDouble(tier7Path);
                if (!(tier7 > 0.0D) || !Double.isFinite(tier7)) continue;

                if (matchesSpeedCurve(yaml, base, tier7, newCurve)) {
                    alreadyNew++;
                    continue;
                }
                if (!matchesSpeedCurve(yaml, base, tier7, oldCurve)) {
                    custom++;
                    continue;
                }

                for (int tier = 1; tier <= 6; tier++) {
                    yaml.set(base + tier + ".action-time-seconds", roundActionTime(tier7 * newCurve[tier - 1]));
                }
                // T7 is deliberately never rewritten.
                migrated++;
            }

            if (migrated > 0) {
                yaml.save(file);
                getLogger().info("Migrated Minion speed curve for " + migrated
                        + " types: T1=3.0x, T2=2.6x, T3=2.2x, T4=1.8x, T5=1.5x, T6=1.2x, T7=1.0x. T7 values were preserved.");
            }
            if (custom > 0) {
                getLogger().info("Left " + custom + " custom Minion speed profiles unchanged; "
                        + alreadyNew + " types already used the new curve.");
            }
        } catch (Exception ex) {
            getLogger().warning("Could not migrate Minion tier speed curve: " + ex.getMessage());
        }
    }

    private static boolean matchesSpeedCurve(YamlConfiguration yaml, String base, double tier7, double[] curve) {
        for (int tier = 1; tier <= 7; tier++) {
            String path = base + tier + ".action-time-seconds";
            if (!yaml.contains(path)) return false;
            double expected = tier7 * curve[tier - 1];
            double actual = yaml.getDouble(path);
            double tolerance = Math.max(0.0001D, Math.abs(expected) * 0.000001D);
            if (Math.abs(actual - expected) > tolerance) return false;
        }
        return true;
    }

    private static double roundActionTime(double value) {
        return Math.round(value * 10000.0D) / 10000.0D;
    }

    private void migrateDynamicUpgradeCostPercents() {
        File file = new File(getDataFolder(), "minion-types.yml");
        if (!file.isFile()) return;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            var root = yaml.getConfigurationSection("minion-types");
            if (root == null) return;
            Map<Integer, Double> oldPercents = Map.of(
                    2, 0.10D, 3, 0.04D, 4, 0.02D, 5, 0.025D, 6, 0.03D, 7, 0.04D);
            Map<Integer, Double> newPercents = Map.of(
                    2, 0.50D, 3, 0.40D, 4, 0.33D, 5, 0.20D, 6, 0.10D, 7, 0.05D);
            int changed = 0;
            int custom = 0;
            for (String minionId : root.getKeys(false)) {
                for (Map.Entry<Integer, Double> entry : newPercents.entrySet()) {
                    int tier = entry.getKey();
                    String path = "minion-types." + minionId + ".tiers." + tier + ".upgrade-requirements.dynamic-collection-cost.percent";
                    if (!yaml.contains(path)) continue;
                    double current = yaml.getDouble(path);
                    if (Math.abs(current - entry.getValue()) <= 0.0000001D) continue;
                    double old = oldPercents.get(tier);
                    if (Math.abs(current - old) > 0.0000001D) {
                        custom++;
                        continue;
                    }
                    yaml.set(path, entry.getValue());
                    changed++;
                }
            }
            if (changed > 0) {
                yaml.save(file);
                getLogger().info("Migrated " + changed + " Minion upgrade cost percentages to T2=50%, T3=40%, T4=33%, T5=20%, T6=10%, T7=5%.");
            }
            if (custom > 0) {
                getLogger().info("Left " + custom + " custom Minion upgrade percentage values unchanged.");
            }
        } catch (Exception ex) {
            getLogger().warning("Could not migrate Minion upgrade cost percentages: " + ex.getMessage());
        }
    }

    private void migrateCompressionUnlockTierOne() {
        try {
            File specialFile = new File(getDataFolder(), "special-items.yml");
            if (specialFile.isFile()) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(specialFile);
                migrateIntIfKnown(yaml, "compression.defaults.unlock-minion-level", 3, 1);
                migrateIntIfKnown(yaml, "compression.defaults.compressed-unlock-minion-level", 3, 1);
                migrateIntIfKnown(yaml, "compression.defaults.super-unlock-minion-level", 5, 1);
                var recipes = yaml.getConfigurationSection("recipes");
                if (recipes != null) {
                    for (String recipeId : recipes.getKeys(false)) {
                        String lower = recipeId.toLowerCase(java.util.Locale.ROOT);
                        if (!lower.startsWith("compressed_") && !lower.startsWith("super_compressed_")) continue;
                        var levels = recipes.getConfigurationSection(recipeId + ".unlock.town-minion-levels");
                        if (levels == null) continue;
                        for (String minionId : levels.getKeys(false)) {
                            int current = levels.getInt(minionId);
                            if (current == 3 || current == 5) levels.set(minionId, 1);
                        }
                    }
                }
                var compressedItem = yaml.getConfigurationSection("special-items.compressed_cobblestone");
                if (compressedItem != null) {
                    java.util.List<String> lore = new java.util.ArrayList<>(compressedItem.getStringList("lore"));
                    for (int i = 0; i < lore.size(); i++) {
                        lore.set(i, lore.get(i).replace("Tier <gold>3</gold>", "Tier <gold>1</gold>")
                                .replace("Tier <gold>5</gold>", "Tier <gold>1</gold>"));
                    }
                    compressedItem.set("lore", lore);
                }
                yaml.save(specialFile);
            }

            File resourcesFile = new File(getDataFolder(), "resources.yml");
            if (resourcesFile.isFile()) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(resourcesFile);
                var root = yaml.getConfigurationSection("resources");
                if (root != null) {
                    for (String resourceId : root.getKeys(false)) {
                        var compression = root.getConfigurationSection(resourceId + ".compression");
                        if (compression == null || !compression.getBoolean("enabled", false)) continue;
                        if (compression.getInt("unlock-minion-level", 3) == 3) compression.set("unlock-minion-level", 1);
                        if (compression.getInt("compressed-unlock-minion-level", 3) == 3) compression.set("compressed-unlock-minion-level", 1);
                        if (compression.getInt("super-unlock-minion-level", 5) == 5) compression.set("super-unlock-minion-level", 1);
                    }
                }
                yaml.save(resourcesFile);
            }
            getLogger().info("Migrated legacy compression unlocks: compressed and super-compressed resources are available from source Minion Tier I.");
        } catch (Exception ex) {
            getLogger().warning("Could not migrate compression unlock levels to Tier I: " + ex.getMessage());
        }
    }

    private static void migrateIntIfKnown(YamlConfiguration yaml, String path, int oldValue, int newValue) {
        // Missing keys used the legacy fallback (3/5), so materialize the new Tier I default too.
        if (!yaml.contains(path) || yaml.getInt(path) == oldValue) yaml.set(path, newValue);
    }

    private boolean ensureDatabaseAvailable() {
        DatabaseAvailability availability = detectDatabaseAvailability();
        if (availability.available()) {
            return true;
        }

        String reason = availability.reason();
        getLogger().severe("HexMinions requires HexCore database, but it is unavailable: " + reason);
        getServer().getPluginManager().disablePlugin(this);
        return false;
    }

    private DatabaseAvailability detectDatabaseAvailability() {
        Object dbService = hex.db();
        try {
            Object available = dbService.getClass().getMethod("isAvailable").invoke(dbService);
            if (available instanceof Boolean value && !value) {
                String reason = "unknown reason";
                try {
                    Object reflectedReason = dbService.getClass().getMethod("unavailableReason").invoke(dbService);
                    if (reflectedReason instanceof String text && !text.isBlank()) {
                        reason = text;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Older HexCore builds do not expose unavailableReason().
                }
                return new DatabaseAvailability(false, reason);
            }
        } catch (NoSuchMethodException ignored) {
            // Older HexCore build: fall back to an actual lightweight query.
        } catch (ReflectiveOperationException | LinkageError ex) {
            return new DatabaseAvailability(false, rootMessage(ex));
        }

        try {
            hex.db().db().queryOne("SELECT 1", rs -> 1);
            return new DatabaseAvailability(true, "");
        } catch (RuntimeException | LinkageError ex) {
            return new DatabaseAvailability(false, rootMessage(ex));
        }
    }

    private record DatabaseAvailability(boolean available, String reason) {
        private DatabaseAvailability {
            if (reason == null || reason.isBlank()) {
                reason = "unknown reason";
            }
        }
    }

    @Override
    public void onDisable() {
        if (advancementService != null) advancementService.shutdown();
        if (musketListener != null) musketListener.shutdown();
        if (robotManager != null) robotManager.shutdown();
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
        if (musketListener != null) musketListener.reload();
        if (robotManager != null) robotManager.reload();
        if (diagnosticsService != null && getConfig().getBoolean("diagnostics.validate-on-startup", true)) diagnosticsService.validateAndLog();
    }

    private void saveResourceIfMissing(String name) {
        if (!new java.io.File(getDataFolder(), name).exists()) saveResource(name, false);
    }

    private void registerUiDefaults() {
        try {
            hex.ui().registerDefaults("minions", Map.ofEntries(
                    Map.entry("help", "<gray>Użyj <yellow>/minion help</yellow>, aby zobaczyć dostępne komendy.</gray>"),
                    Map.entry("help.player", "<gold>HexMinions</gold><newline><yellow>/minion list</yellow> <gray>— lista minionów miasta</gray><newline><yellow>/minion wiki</yellow> <gray>— wiki minionów</gray><newline><yellow>/minion wiki electronics</yellow> <gray>— wiki elektroniki i maszyn</gray><newline><yellow>/minion help</yellow> <gray>— pomoc</gray>"),
                    Map.entry("help.admin", "<dark_gray>Administracja:</dark_gray><newline><yellow>/minion give ...</yellow><newline><yellow>/minion reload</yellow><newline><yellow>/minion admin ...</yellow><newline><yellow>/minion pickup UUID</yellow><newline><yellow>/minion move UUID</yellow><newline><yellow>/minion select UUID</yellow><newline><yellow>/minion select-index INDEX</yellow><newline><yellow>/minion action ...</yellow><newline><yellow>/minion wiki TYP</yellow>"),
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
                    Map.entry("error.storage-empty", "<gray>Magazyn miniona jest pusty.</gray>"),
                    Map.entry("error.invalid-menu-item", "<red>Tego itemu nie mozna wlozyc w ten slot miniona.</red>"),
                    Map.entry("pickup.error.addons-not-empty", "<red>Najpierw wyjmij specjalne dodatki z miniona.</red>"),
                    Map.entry("storage-chest.error.no-space-left", "<red>Po lewej stronie miniona nie ma miejsca na skrzynkę magazynową.</red>"),
                    Map.entry("storage-chest.error.break-linked", "<red>Tej skrzynki nie mozna zniszczyc recznie. Najpierw obsluz ja z menu miniona.</red>"),
                    Map.entry("place.success", "<green>Postawiono miniona <white><id></white>.</green>"),
                    Map.entry("pickup.success", "<green>Podniesiono miniona <white><id></white>.</green>"),
                    Map.entry("collect.success", "<green>Odebrano surowce z miniona.</green>"),
                    Map.entry("upgrade.success", "<green>Ulepszono miniona do tier <white><tier></white>.</green>"),
                    Map.entry("upgrade.missing-requirements", "<red>Nie spelniasz wymagan ulepszenia.</red>"),
                    Map.entry("storage-chest.place.success", "<green>Podłączono skrzynkę magazynową do miniona.</green>"),
                    Map.entry("storage-chest.install.success", "<green>Utworzono i podłączono skrzynkę magazynową po lewej stronie miniona.</green>"),
                    Map.entry("storage-chest.uninstall.success", "<green>Odłączono skrzynkę magazynową. Jej zawartość wypadła obok miniona.</green>"),
                    Map.entry("storage-chest.error.not-found", "<gray>Ten minion nie ma podłączonej skrzynki magazynowej.</gray>"),
                    Map.entry("storage-chest.error.special-required", "<red>Obok miniona można postawić tylko skonfigurowany magazyn miniona.</red>"),
                    Map.entry("storage-chest.error.already-has", "<red>Ten minion ma już skrzynkę magazynową.</red>"),
                    Map.entry("storage-chest.error.next-to-chest", "<red>Nie możesz postawić magazynu miniona obok innej skrzynki.</red>"),
                    Map.entry("special-crafting.error.no-town", "<red>Ten crafting wymaga miasta.</red>"),
                    Map.entry("special-crafting.error.locked", "<red>Nie spelniasz wymagan tej receptury.</red>"),
                    Map.entry("special-crafting.error.no-match", "<red>Nie wykryto poprawnej receptury.</red>"),
                    Map.entry("special-crafting.error.no-space", "<red>W tym miejscu nie mozna postawic tego bloku.</red>"),
                    Map.entry("special-crafting.error.place-town", "<red>Ten blok mozesz postawic tylko na terenie swojego miasta.</red>"),
                    Map.entry("special-crafting.error.near-heart", "<red>Maszyn nie mozna stawiac w chronionym chunku Serca Miasta.</red>"),
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
                    Map.entry("usage.give", "<yellow>Uzycie: <white>/minion give <gracz> <typ> [tier] [ilosc]</white></yellow>"),
                    Map.entry("usage.player-action", "<yellow>Uzycie: <white>/minion <action> <id></white></yellow>"),
                    Map.entry("usage.action", "<yellow>Uzycie: <white>/minion action <collect|upgrade|pickup|move|open> <id></white></yellow>"),
                    Map.entry("usage.select", "<yellow>Uzycie: <white>/minion select <id></white></yellow>"),
                    Map.entry("admin.usage", "<yellow>Komendy admina: <white>/minion admin validate</white>, <white>/minion admin balance-report</white>, <white>/minion admin addlimit <uuid-miasta|nazwa-miasta> <bonus> [zrodlo]</white></yellow>"),
                    Map.entry("admin.addlimit.usage", "<red>Uzycie: <white>/minion admin addlimit <uuid-miasta|nazwa-miasta> <bonus> [zrodlo]</white></red>"),
                    Map.entry("admin.addlimit.town-not-found", "<red>Nie znaleziono miasta: <white><town></white></red>"),
                    Map.entry("admin.addlimit.zero", "<red>Bonus musi byc liczba rozna od 0.</red>"),
                    Map.entry("admin.addlimit.success", "<green>Dodano bonus limitu minionow <white><delta></white> dla miasta <yellow><town></yellow>. Aktualny limit: <white><max></white>. Zrodlo: <gray><source></gray>.</green>"),
                    Map.entry("admin.metrics", "<green>HexMinions metrics: podstawowy MVP dziala.</green>"),
                    Map.entry("cable.loading", "<red>System kabli jeszcze sie laduje. Sprobuj ponownie za chwile.</red>"),
                    Map.entry("cable.invalid-shape", "<red>Kabel musi byc prostym odcinkiem i dotykac portu maszyny albo istniejacego kabla.</red>"),
                    Map.entry("cable.validation-error", "<red><error></red>"),
                    Map.entry("cable.place.not-town", "<red>Kable mozesz stawiac tylko w swoim miescie.</red>"),
                    Map.entry("cable.place.success", "<green>Polozono <white><cable></white> <gray>(<length>m)</gray>. <dark_gray>Kabel jest pasywnym segmentem sieci EU.</dark_gray></green>"),
                    Map.entry("cable.remove.not-town", "<red>Mozesz usuwac kable tylko w swoim miescie.</red>"),
                    Map.entry("cable.info", "<gray>Kabel:</gray> <white><cable></white> <dark_gray>|</dark_gray> <gray>dlugosc:</gray> <white><length>m</white> <dark_gray>|</dark_gray> <gray>limit:</gray> <white><limit> EU/s</white> <dark_gray>|</dark_gray> <gray>strata:</gray> <white><loss> EU/m</white>"),
                    Map.entry("cable.remove.success", "<yellow>Usunieto caly segment kabla, nie pojedynczy metr.</yellow>"),
                    Map.entry("energy.limit-reached", "<red>Nie mozna postawic elementu energii: <white><error></white></red>"),
                    Map.entry("machine.error.not-town", "<red>Mozesz obslugiwac te maszyne tylko w swoim miescie.</red>"),
                    Map.entry("machine.accumulator.input-face-required", "<red>Kliknij konkretny bok akumulatora, ktory ma zostac wejsciem EU.</red>"),
                    Map.entry("machine.accumulator.input-face-same", "<yellow>Ten bok jest juz wejsciem EU akumulatora.</yellow>"),
                    Map.entry("machine.accumulator.input-face-changed", "<green>Przeniesiono wejscie EU akumulatora na bok: <white><face></white>. Poprzedni bok stal sie wyjsciem.</green>"),
                    Map.entry("machine.dismantle.success", "<yellow>Rozkrecono maszyne. Item maszyny i zawartosc wypadly obok.</yellow>"),
                    Map.entry("radiation.actionbar", "<green>☢</green> <red>Promieniowanie:</red> <white><amount>x wzbogacony uran</white> <gray>(ochrona <protection>%)</gray>"),
                    Map.entry("radiation.chest-warning", "<red>W skrzyni znajduje sie wzbogacony uran. Ochrona kombinezonu: <white><protection>%</white>.</red>"),
                    Map.entry("radiation.chest-blocked", "<red>Wzbogaconego uranu nie mozna wkladac do zwyklej skrzyni.</red>"),
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


