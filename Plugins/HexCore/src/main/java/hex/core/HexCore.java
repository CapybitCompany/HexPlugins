package hex.core;

import hex.core.api.HexApi;
import hex.core.api.config.ConfigSpec;
import hex.core.api.config.ReloadPolicy;
import hex.core.api.db.DatabaseService;
import hex.core.command.HexCoreVersion;
import hex.core.command.UiTemplateCommand;
import hex.core.command.region.RegionCommand;
import hex.core.service.HexApiImpl;
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


        // 2) API provider
        this.api = new HexApiImpl(configs, flags, ui, regionService, database);

        // 3) Register in ServicesManager
        Bukkit.getServicesManager().register(HexApi.class, api, this, ServicePriority.Normal);

        // 4) Commands (tylko HexCore)
        getCommand("hexcore").setExecutor(new HexCoreVersion(this));

        RegionCommand regionCmd = new RegionCommand(this, this.api);
        getCommand("region").setExecutor(regionCmd);
        getCommand("region").setTabCompleter(regionCmd);

        UiTemplateCommand uiCmd = new UiTemplateCommand(this.api);
        getCommand("uitpl").setExecutor(uiCmd);
        getCommand("uitpl").setTabCompleter(uiCmd);

        getLogger().info("HexCore enabled ✅");
    }

    @Override
    public void onDisable() {
        if (database != null) database.shutdown();

        Bukkit.getServicesManager().unregister(HexApi.class, api);
        getLogger().info("HexCore disabled ❌");
    }
}