package hex.core;

import hex.core.api.compat.MinecraftCompatibility;
import hex.core.api.HexApi;
import hex.core.api.config.ConfigSpec;
import hex.core.api.config.ReloadResult;
import hex.core.api.config.ReloadPolicy;
import hex.core.api.db.DatabaseService;
import hex.core.api.messaging.HexMessageBus;
import hex.core.api.trigger.TriggerService;
import hex.core.command.HexCoreReload;
import hex.core.command.UiTemplateCommand;
import hex.core.command.region.RegionCommand;
import hex.core.command.HexDebugDbCommand;
import hex.core.command.CoinsCacheCommand;
import hex.core.database.repository.CoinsRepository;
import hex.core.database.repository.RankingPointsRepository;
import hex.core.placeholder.HexPlaceholderExpansion;
import hex.core.placeholder.HexPlaceholderRegistry;
import hex.core.placeholder.provider.CoinsPlaceholderProvider;
import hex.core.placeholder.provider.GlobalPointsPlaceholderProvider;
import hex.core.placeholder.provider.SeasonPointsPlaceholderProvider;
import hex.core.placeholder.provider.TopMoneyPlaceholderProvider;
import hex.core.placeholder.provider.TopRankingPlaceholderProvider;
import hex.core.placeholder.provider.RankPositionPlaceholderProvider;
import hex.core.service.HexApiImpl;
import hex.core.service.coins.CoinsService;
import hex.core.service.ranking.RankingPointsService;
import hex.core.service.ranking.MoneyTopService;
import hex.core.service.ranking.RankingTopService;
import hex.core.service.ranking.RankingPositionService;
import hex.core.service.config.ConfigServiceImpl;
import hex.core.service.db.DbConfig;
import hex.core.service.db.DbConfigLoader;
import hex.core.service.db.DataSourceBackedDatabaseService;
import hex.core.service.db.DriverManagerDatabaseService;
import hex.core.service.db.HikariDatabaseService;
import hex.core.service.db.NoopDatabaseService;
import hex.core.service.db.ReloadableDatabaseService;
import hex.core.service.flags.FeatureFlagServiceImpl;
import hex.core.service.flags.FlagsConfig;
import hex.core.service.flags.FlagsValidator;
import hex.core.service.region.RegionServiceImpl;
import hex.core.service.ui.UiConfig;
import hex.core.service.ui.UiServiceImpl;
import hex.core.service.ui.UiValidator;
import hex.core.service.cache.PlayerStatsCacheService;
import hex.core.service.messaging.HexMessageBusImpl;
import hex.core.service.trigger.HexTriggerService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HexCore extends JavaPlugin {

    private HexApi api; // provider rejestrowany w ServicesManager
    private DatabaseService database;
    private ReloadableDatabaseService reloadableDatabase;
    private HexPlaceholderExpansion placeholderExpansion;

    // Shared ranking services used by PlaceholderAPI + cache invalidation.
    private RankingTopService rankingTopService;
    private RankingPositionService rankingPositionService;

    @Override
    public void onEnable() {
        MinecraftCompatibility.logStartupCompatibility(this);
        // 1) Services
        var configs = new ConfigServiceImpl(this);

        Path data = getDataFolder().toPath();

        saveResource("ui.yml", false);
        saveResource("flags.yml", false);
        saveResource("db.yml", false);
        mergeBundledUiDefaults(data.resolve("ui.yml").toFile());

        DbConfig dbCfg = new DbConfigLoader().load(new java.io.File(getDataFolder(), "db.yml"));
        DatabaseService databaseDelegate;
        try {
            databaseDelegate = createDatabaseDelegate(dbCfg);
        } catch (Exception ex) {
            if (dbCfg.required) {
                getLogger().severe("[DB] Failed to initialize: " + ex.getMessage());
                getLogger().severe("[DB] required=true -> disabling plugin");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            getLogger().warning("[DB] Failed to initialize; required=false -> using noop database service: " + ex.getMessage());
            databaseDelegate = new NoopDatabaseService("DB init failed (required=false): " + ex.getMessage());
        }
        this.reloadableDatabase = new ReloadableDatabaseService(this, databaseDelegate);
        this.database = reloadableDatabase;

        // ui.yml
        configs.register(new ConfigSpec<>(
                UiServiceImpl.UI_KEY,
                data.resolve("ui.yml"),
                UiConfig::new,
                new UiValidator(),
                ReloadPolicy.HOT
        ));

        // flags.yml (możesz dodać później, ale nie szkodzi)
        configs.register(new ConfigSpec<>(
                FeatureFlagServiceImpl.FLAGS_KEY,
                data.resolve("flags.yml"),
                FlagsConfig::new,
                new FlagsValidator(),
                ReloadPolicy.HOT
        ));

        var ui = new UiServiceImpl(configs);
        var flags = new FeatureFlagServiceImpl(configs);
        var regionService = new RegionServiceImpl(new java.io.File(getDataFolder(), "regions.yml"));

        DatabaseBackedServices dbServices = createDatabaseBackedServices(dbCfg, databaseDelegate);

        RankingPointsService rankingPointsService = dbServices.rankingPointsService();
        CoinsService coinsService = dbServices.coinsService();

        // Ranking extras used by PlaceholderAPI (and exposed for cache invalidation)
        this.rankingTopService = dbServices.rankingTopService();
        this.rankingPositionService = dbServices.rankingPositionService();

        // 2) API provider (stable core constructor)
        HexApiImpl apiImpl = new HexApiImpl(configs, flags, ui, regionService, database,
                rankingPointsService, coinsService);

        // Generic cross-plugin messaging transport + gameplay trigger convention (trigger.*).
        HexMessageBus messageBus = new HexMessageBusImpl();
        apiImpl.registerService(HexMessageBus.class, messageBus);
        apiImpl.registerService(TriggerService.class, new HexTriggerService(messageBus));

        // Optional extensions registered transparently for consumers via api.service(...)
        apiImpl.registerService(RankingPositionService.class, this.rankingPositionService);
        apiImpl.registerService(RankingTopService.class, this.rankingTopService);
        apiImpl.registerService(PlayerStatsCacheService.class,
                new PlayerStatsCacheService(coinsService, rankingPointsService,
                        this.rankingPositionService, this.rankingTopService));

        this.api = apiImpl;

        // 3) Register in ServicesManager
        Bukkit.getServicesManager().register(HexApi.class, api, this, ServicePriority.Normal);

        // 4) Commands (tylko HexCore)
        HexCoreReload hexCoreCommand = new HexCoreReload(this);
        getCommand("hexcore").setExecutor(hexCoreCommand);
        getCommand("hexcore").setTabCompleter(hexCoreCommand);

        // debug read command
        if (getCommand("hexdebugdb") != null) {
            getCommand("hexdebugdb").setExecutor(new HexDebugDbCommand(this.api));
        }

        RegionCommand regionCmd = new RegionCommand(this, this.api);
        getCommand("region").setExecutor(regionCmd);
        getCommand("region").setTabCompleter(regionCmd);

        UiTemplateCommand uiCmd = new UiTemplateCommand(this.api);
        getCommand("uitpl").setExecutor(uiCmd);
        getCommand("uitpl").setTabCompleter(uiCmd);

        if (getCommand("hexcoinscache") != null) {
            CoinsCacheCommand coinsCacheCommand = new CoinsCacheCommand(this.api);
            getCommand("hexcoinscache").setExecutor(coinsCacheCommand);
            getCommand("hexcoinscache").setTabCompleter(coinsCacheCommand);
        }

        registerPlaceholderExpansion();

        if (dbCfg.debug) {
            getLogger().info("[DB][DEBUG] ranking_points table = '" + (dbCfg.tablePrefix == null ? "" : dbCfg.tablePrefix) + "ranking_points'");
            getLogger().info("[DB][DEBUG] coins table = 'xeconomy' (no prefix)");
        }

        getLogger().info("HexCore enabled ✅");
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }

        if (database != null) database.shutdown();

        Bukkit.getServicesManager().unregister(HexApi.class, api);
        getLogger().info("HexCore disabled ❌");
    }

    private void registerPlaceholderExpansion() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("[PAPI] PlaceholderAPI not found, skipping expansion registration.");
            return;
        }

        try {
            HexPlaceholderRegistry registry = new HexPlaceholderRegistry();
            registry.register(new GlobalPointsPlaceholderProvider());
            registry.register(new SeasonPointsPlaceholderProvider());
            registry.register(new CoinsPlaceholderProvider());

            // Placeholders that require DB-backed services.
            if (reloadableDatabase != null && reloadableDatabase.isActiveHikari()) {
                // TOP 5 (prefix providers)
                TopRankingPlaceholderProvider topProvider = new TopRankingPlaceholderProvider(this.rankingTopService);
                registry.registerPrefix("top_global_", topProvider);
                registry.registerPrefix("top_season_", topProvider);

                // TOP 10 wydanych pieniędzy z vishop_player_totals
                MoneyTopService moneyTopService = new MoneyTopService(api.db().db());
                TopMoneyPlaceholderProvider topMoneyProvider = new TopMoneyPlaceholderProvider(moneyTopService);
                registry.registerPrefix("top_money_", topMoneyProvider);

                // Player rank position
                registry.register(new RankPositionPlaceholderProvider("rank_global", this.rankingPositionService));
                registry.register(new RankPositionPlaceholderProvider("rank_season", this.rankingPositionService));
            }

            this.placeholderExpansion = new HexPlaceholderExpansion(this, api, registry);
            if (placeholderExpansion.register()) {
                getLogger().info("[PAPI] Registered expansion %hex_%.");
            } else {
                getLogger().warning("[PAPI] Failed to register expansion %hex_%.");
                this.placeholderExpansion = null;
            }
        } catch (NoClassDefFoundError error) {
            getLogger().warning("[PAPI] PlaceholderAPI classes missing at runtime: " + error.getMessage());
        }
    }

    public ReloadResult reloadHotConfigs() {
        ReloadResult ui = reloadUiConfig();
        if (!ui.success()) {
            return ui;
        }

        ReloadResult flags = reloadFlagsConfig();
        if (!flags.success()) {
            return flags;
        }

        return ReloadResult.ok("Reloaded ui and flags");
    }

    public ReloadResult reloadUiConfig() {
        mergeBundledUiDefaults(getDataFolder().toPath().resolve("ui.yml").toFile());
        return api.configs().reload("ui");
    }

    public ReloadResult reloadFlagsConfig() {
        return api.configs().reload("flags");
    }

    public ReloadResult reloadDatabase() {
        DbConfig dbCfg = new DbConfigLoader().load(new java.io.File(getDataFolder(), "db.yml"));
        DatabaseService newDelegate;
        try {
            newDelegate = createDatabaseDelegate(dbCfg);
        } catch (Exception ex) {
            getLogger().severe("[DB] Reload failed; keeping current connection: " + ex.getMessage());
            return ReloadResult.failed("DB reload failed; current connection was kept: " + ex.getMessage(), java.util.List.of());
        }

        DatabaseBackedServices dbServices = createDatabaseBackedServices(dbCfg, newDelegate);
        reloadableDatabase.swap(newDelegate);

        this.rankingTopService = dbServices.rankingTopService();
        this.rankingPositionService = dbServices.rankingPositionService();
        if (api instanceof HexApiImpl apiImpl) {
            apiImpl.updateDatabaseBackedServices(
                    dbServices.rankingPointsService(),
                    dbServices.coinsService(),
                    dbServices.rankingPositionService(),
                    dbServices.rankingTopService()
            );
        }

        reloadPlaceholderExpansion();

        if (dbCfg.debug) {
            getLogger().info("[DB][DEBUG] ranking_points table = '" + (dbCfg.tablePrefix == null ? "" : dbCfg.tablePrefix) + "ranking_points'");
            getLogger().info("[DB][DEBUG] coins table = 'xeconomy' (no prefix)");
        }

        return ReloadResult.ok(dbCfg.enabled ? "DB reloaded and reconnected" : "DB disabled; switched to noop database service");
    }

    private void reloadPlaceholderExpansion() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        registerPlaceholderExpansion();
    }

    private DatabaseService createDatabaseDelegate(DbConfig dbCfg) {
        if (dbCfg.enabled) {
            try {
                DatabaseService service = new HikariDatabaseService(this, dbCfg);
                validateDatabaseConnection(service);
                getLogger().info("[DB] Enabled with HikariCP ✅");
                return service;
            } catch (NoClassDefFoundError | ExceptionInInitializerError error) {
                getLogger().warning("[DB] HikariCP is not available in HexCore classloader; using DriverManager fallback. Cause: " + error.getMessage());
                DatabaseService service = new DriverManagerDatabaseService(this, dbCfg);
                validateDatabaseConnection(service);
                getLogger().info("[DB] Enabled with DriverManager fallback ✅");
                return service;
            }
        }

        getLogger().warning("[DB] Disabled in db.yml");
        return new NoopDatabaseService("DB disabled in db.yml");
    }

    private void validateDatabaseConnection(DatabaseService service) {
        try {
            service.db().queryOne("SELECT 1", rs -> 1);
        } catch (RuntimeException ex) {
            service.shutdown();
            throw ex;
        }
    }

    private DatabaseBackedServices createDatabaseBackedServices(DbConfig dbCfg, DatabaseService activeDatabase) {
        RankingPointsRepository rankingPointsRepository = null;
        CoinsRepository coinsRepository = null;
        if (activeDatabase instanceof DataSourceBackedDatabaseService dataSourceDb) {
            rankingPointsRepository = new RankingPointsRepository(dataSourceDb.dataSource(), dbCfg.tablePrefix);
            coinsRepository = new CoinsRepository(dataSourceDb.dataSource(), "");
        }

        RankingPointsService rankingPointsService = new RankingPointsService(rankingPointsRepository, activeDatabase);
        CoinsService coinsService = new CoinsService(coinsRepository, activeDatabase);
        RankingTopService rankingTopService = new RankingTopService(rankingPointsRepository, activeDatabase);
        RankingPositionService rankingPositionService = new RankingPositionService(rankingPointsRepository, activeDatabase);
        return new DatabaseBackedServices(rankingPointsService, coinsService, rankingTopService, rankingPositionService);
    }

    private record DatabaseBackedServices(RankingPointsService rankingPointsService,
                                          CoinsService coinsService,
                                          RankingTopService rankingTopService,
                                          RankingPositionService rankingPositionService) {
    }

    @SuppressWarnings("unchecked")
    private void mergeBundledUiDefaults(File targetFile) {
        if (targetFile == null || !targetFile.exists()) {
            return;
        }

        Yaml yaml = new Yaml();
        try (InputStream bundledStream = getResource("ui.yml");
             InputStream targetStream = Files.newInputStream(targetFile.toPath())) {
            if (bundledStream == null) {
                return;
            }

            Map<String, Object> bundled = yaml.load(bundledStream);
            Map<String, Object> target = yaml.load(targetStream);
            if (bundled == null || target == null) {
                return;
            }

            boolean changed = false;
            changed |= mergeMissingMapValues(bundled, target, "prefixes");
            changed |= mergeMissingMapValues(bundled, target, "templates");
            changed |= mergeMissingMapValues(bundled, target, "templateArgs");

            if (changed) {
                DumperOptions options = new DumperOptions();
                options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
                options.setPrettyFlow(true);
                Files.writeString(targetFile.toPath(), new Yaml(options).dump(target), StandardCharsets.UTF_8);
                getLogger().info("ui.yml: dopisano brakujace domyslne wpisy UI z aktualnego HexCore.jar.");
            }
        } catch (IOException | ClassCastException e) {
            getLogger().warning("Nie mozna uzupelnic ui.yml o domyslne wpisy: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private boolean mergeMissingMapValues(Map<String, Object> source, Map<String, Object> target, String sectionKey) {
        Object sourceRaw = source.get(sectionKey);
        if (!(sourceRaw instanceof Map<?, ?> sourceSectionRaw)) {
            return false;
        }

        Object targetRaw = target.get(sectionKey);
        Map<String, Object> targetSection;
        boolean changed = false;
        if (targetRaw instanceof Map<?, ?> targetSectionRaw) {
            targetSection = (Map<String, Object>) targetSectionRaw;
        } else {
            targetSection = new LinkedHashMap<>();
            target.put(sectionKey, targetSection);
            changed = true;
        }

        Map<String, Object> sourceSection = (Map<String, Object>) sourceSectionRaw;
        for (Map.Entry<String, Object> entry : sourceSection.entrySet()) {
            if (!targetSection.containsKey(entry.getKey())) {
                targetSection.put(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        return changed;
    }
}
