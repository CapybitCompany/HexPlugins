package hex.core;

import hex.core.api.HexApi;
import hex.core.api.config.ConfigSpec;
import hex.core.api.config.ReloadPolicy;
import hex.core.api.db.DatabaseService;
import hex.core.command.HexCoreVersion;
import hex.core.command.UiTemplateCommand;
import hex.core.command.region.RegionCommand;
import hex.core.command.HexDebugDbCommand;
import hex.core.database.repository.CoinsRepository;
import hex.core.database.repository.RankingPointsRepository;
import hex.core.placeholder.HexPlaceholderExpansion;
import hex.core.placeholder.HexPlaceholderRegistry;
import hex.core.placeholder.provider.CoinsPlaceholderProvider;
import hex.core.placeholder.provider.GlobalPointsPlaceholderProvider;
import hex.core.placeholder.provider.SeasonPointsPlaceholderProvider;
import hex.core.placeholder.provider.TopRankingPlaceholderProvider;
import hex.core.placeholder.provider.RankPositionPlaceholderProvider;
import hex.core.service.HexApiImpl;
import hex.core.service.coins.CoinsService;
import hex.core.service.ranking.RankingPointsService;
import hex.core.service.ranking.RankingTopService;
import hex.core.service.ranking.RankingPositionService;
import hex.core.service.config.ConfigServiceImpl;
import hex.core.service.db.DbConfig;
import hex.core.service.db.DbConfigLoader;
import hex.core.service.db.HikariDatabaseService;
import hex.core.service.db.NoopDatabaseService;
import hex.core.service.flags.FeatureFlagServiceImpl;
import hex.core.service.flags.FlagsConfig;
import hex.core.service.flags.FlagsValidator;
import hex.core.service.region.RegionServiceImpl;
import hex.core.service.ui.UiConfig;
import hex.core.service.ui.UiServiceImpl;
import hex.core.service.ui.UiValidator;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public final class HexCore extends JavaPlugin {

    private HexApi api; // provider rejestrowany w ServicesManager
    private DatabaseService database;
    private HexPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        // 1) Services
        var configs = new ConfigServiceImpl(this);

        Path data = getDataFolder().toPath();

        saveResource("ui.yml", false);
        saveResource("flags.yml", false);
        saveResource("db.yml", false);

        DbConfig dbCfg = new DbConfigLoader().load(new java.io.File(getDataFolder(), "db.yml"));
        try {
            if (dbCfg.enabled) {
                database = new HikariDatabaseService(this, dbCfg);
                getLogger().info("[DB] Enabled ✅");
            } else {
                database = new NoopDatabaseService("DB disabled in db.yml");
                getLogger().warning("[DB] Disabled in db.yml");
            }
        } catch (Exception ex) {
            getLogger().severe("[DB] Failed to initialize: " + ex.getMessage());

            if (dbCfg.required) {
                getLogger().severe("[DB] required=true -> disabling plugin");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            database = new NoopDatabaseService("DB init failed (required=false): " + ex.getMessage());
        }

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

        RankingPointsRepository rankingPointsRepository = null;
        CoinsRepository coinsRepository = null;
        if (database instanceof HikariDatabaseService hikariDb) {
            rankingPointsRepository = new RankingPointsRepository(hikariDb.dataSource(), dbCfg.tablePrefix);
            coinsRepository = new CoinsRepository(hikariDb.dataSource(), "");
        }

        RankingPointsService rankingPointsService = new RankingPointsService(rankingPointsRepository);
        CoinsService coinsService = new CoinsService(coinsRepository);

        // 2) API provider
        this.api = new HexApiImpl(configs, flags, ui, regionService, database, rankingPointsService, coinsService);

        // 3) Register in ServicesManager
        Bukkit.getServicesManager().register(HexApi.class, api, this, ServicePriority.Normal);

        // 4) Commands (tylko HexCore)
        getCommand("hexcore").setExecutor(new HexCoreVersion(this));

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

            if (database instanceof HikariDatabaseService hikariDb) {
                var repo = new RankingPointsRepository(hikariDb.dataSource(), new DbConfigLoader().load(new java.io.File(getDataFolder(), "db.yml")).tablePrefix);

                // TOP 5
                RankingTopService topService = new RankingTopService(repo);
                TopRankingPlaceholderProvider topProvider = new TopRankingPlaceholderProvider(topService);
                registry.registerPrefix("top_global_", topProvider);
                registry.registerPrefix("top_season_", topProvider);

                // Player rank position
                RankingPositionService rankPos = new RankingPositionService(repo);
                registry.register(new RankPositionPlaceholderProvider("rank_global", rankPos));
                registry.register(new RankPositionPlaceholderProvider("rank_season", rankPos));
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
}