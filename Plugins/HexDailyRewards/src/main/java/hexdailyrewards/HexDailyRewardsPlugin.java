package hexdailyrewards;

import hexdailyrewards.command.HexDailyRewardsCommand;
import hexdailyrewards.config.DailyRewardsConfig;
import hexdailyrewards.config.DailyRewardsConfigLoader;
import hexdailyrewards.gui.DailyRewardsGui;
import hexdailyrewards.gui.DailyRewardsInventoryListener;
import hexdailyrewards.integration.HexDailyRewardsPlaceholderExpansion;
import hexdailyrewards.integration.HexNpcActionBridge;
import hexdailyrewards.storage.YamlRewardStorage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class HexDailyRewardsPlugin extends JavaPlugin {

    private static final int CONFIG_VERSION = 2;

    private final DailyRewardsConfigLoader configLoader = new DailyRewardsConfigLoader();
    private final AtomicReference<DailyRewardsConfig> configRef = new AtomicReference<>();

    private YamlRewardStorage storage;
    private DailyRewardService rewardService;
    private DailyRewardsGui gui;
    private DailyRewardsInventoryListener inventoryListener;
    private HexNpcActionBridge hexNpcActionBridge;
    private Object placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        applyConfigMaintenance();

        this.storage = new YamlRewardStorage(new File(getDataFolder(), "claims.yml"));
        storage.load();

        if (!reloadPluginConfig()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.rewardService = new DailyRewardService(this, storage, configRef::get, Clock.systemUTC());
        this.gui = new DailyRewardsGui(this, rewardService, configRef::get);
        this.inventoryListener = new DailyRewardsInventoryListener(gui);
        getServer().getPluginManager().registerEvents(inventoryListener, this);

        HexDailyRewardsCommand command = new HexDailyRewardsCommand(this);
        var pluginCommand = getCommand("hexdailyrewards");
        if (pluginCommand == null) {
            getLogger().severe("Command 'hexdailyrewards' missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        this.hexNpcActionBridge = new HexNpcActionBridge(this, configRef::get, this::openRewards);
        getServer().getPluginManager().registerEvents(hexNpcActionBridge, this);
        hexNpcActionBridge.refresh();

        registerPlaceholderExpansion();

        getLogger().info("HexDailyRewards enabled.");
    }

    @Override
    public void onDisable() {
        if (hexNpcActionBridge != null) {
            hexNpcActionBridge.unregister();
            hexNpcActionBridge = null;
        }
        if (inventoryListener != null) {
            HandlerList.unregisterAll(inventoryListener);
            inventoryListener = null;
        }
        unregisterPlaceholderExpansion();
        gui = null;
        rewardService = null;
        storage = null;
        getLogger().info("HexDailyRewards disabled.");
    }

    public boolean reloadPluginRuntime() {
        reloadConfig();
        applyConfigMaintenance();
        boolean loaded = reloadPluginConfig();
        if (loaded && hexNpcActionBridge != null) {
            hexNpcActionBridge.refresh();
        }
        return loaded;
    }

    public void openRewards(Player player) {
        Objects.requireNonNull(player, "player");
        DailyRewardsConfig config = config();
        if (config == null || !config.enabled()) {
            player.sendMessage(Text.component(config == null ? "&cDaily Rewards disabled." : config.messages().disabled()));
            return;
        }
        if (!player.hasPermission("hexdailyrewards.use")) {
            player.sendMessage(Text.component(config.messages().withPrefix(config.messages().noPermission())));
            return;
        }
        gui.open(player);
        ClaimState state = rewardService.state(player);
        if (!state.available()) {
            player.sendActionBar(Text.component(config.messages().alreadyClaimedActionbar(),
                    rewardService.placeholders(player, state)));
        }
    }

    public DailyRewardsConfig config() {
        return configRef.get();
    }

    public DailyRewardService rewardService() {
        return rewardService;
    }

    public DailyRewardsGui gui() {
        return gui;
    }

    private boolean reloadPluginConfig() {
        try {
            configRef.set(configLoader.load(getConfig(), getLogger()));
            return true;
        } catch (RuntimeException ex) {
            getLogger().severe("Failed to load HexDailyRewards config: " + ex.getMessage());
            return false;
        }
    }

    private void applyConfigMaintenance() {
        FileConfiguration config = getConfig();
        int version = config.isSet("config-version") ? config.getInt("config-version") : 1;

        config.options().copyDefaults(true);
        if (version < 2) {
            migrateVisualDefaults(config);
            getLogger().info("Migrated HexDailyRewards config visuals to version 2.");
        }
        config.set("config-version", CONFIG_VERSION);

        saveConfig();
        reloadConfig();
    }

    private void migrateVisualDefaults(FileConfiguration config) {
        config.set("gui.title", "&cDaily Rewards");
        config.set("gui.filler.material", "BLACK_STAINED_GLASS_PANE");
        config.set("gui.filler.name", "");
        config.set("gui.filler.lore", List.of());
        config.set("gui.filler.hide_tooltip", true);

        config.set("gui.items.available.slot", 13);
        config.set("gui.items.available.use-reward-material", true);
        config.set("gui.items.available.name", "{reward_name}");
        config.set("gui.items.available.lore", List.of("{reward_lore}"));

        config.set("gui.items.claimed.slot", 13);
        config.set("gui.items.claimed.use-reward-material", true);
        config.set("gui.items.claimed.name", "{reward_name}");
        config.set("gui.items.claimed.lore", List.of("{reward_lore}"));

        config.set("gui.items.status-available.slot", 26);
        config.set("gui.items.status-available.material", "LIME_DYE");
        config.set("gui.items.status-available.name", "&fStatus: &aDo odebrania");
        config.set("gui.items.status-available.lore", List.of("&7Do następnej nagrody: &f{time}"));

        config.set("gui.items.status-claimed.slot", 26);
        config.set("gui.items.status-claimed.material", "RED_DYE");
        config.set("gui.items.status-claimed.name", "&fStatus: &cOdebrane");
        config.set("gui.items.status-claimed.lore", List.of("&7Do następnej nagrody: &f{time}"));

        config.set("gui.items.info.enabled", false);

        config.set("gui.items.close.slot", 18);
        config.set("gui.items.close.material", "BARRIER");
        config.set("gui.items.close.name", "&cZamknij");
        config.set("gui.items.close.lore", List.of());
    }

    private void registerPlaceholderExpansion() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().info("PlaceholderAPI not detected. HexDailyRewards placeholders are disabled.");
            return;
        }
        try {
            HexDailyRewardsPlaceholderExpansion expansion = new HexDailyRewardsPlaceholderExpansion(this);
            if (expansion.register()) {
                placeholderExpansion = expansion;
                getLogger().info("Registered PlaceholderAPI placeholders.");
            } else {
                getLogger().warning("Failed to register PlaceholderAPI placeholders.");
            }
        } catch (NoClassDefFoundError ex) {
            getLogger().info("PlaceholderAPI classes are unavailable. HexDailyRewards placeholders are disabled.");
        }
    }

    private void unregisterPlaceholderExpansion() {
        if (placeholderExpansion == null) {
            return;
        }
        try {
            placeholderExpansion.getClass().getMethod("unregister").invoke(placeholderExpansion);
        } catch (ReflectiveOperationException ex) {
            getLogger().warning("Failed to unregister PlaceholderAPI placeholders: " + ex.getMessage());
        } finally {
            placeholderExpansion = null;
        }
    }
}
