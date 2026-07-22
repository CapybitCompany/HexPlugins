package hexdailyrewards;

import hexdailyrewards.command.HexDailyRewardsCommand;
import hexdailyrewards.config.DailyRewardsConfig;
import hexdailyrewards.config.DailyRewardsConfigLoader;
import hexdailyrewards.gui.DailyRewardsGui;
import hexdailyrewards.gui.DailyRewardsInventoryListener;
import hexdailyrewards.integration.HexDailyRewardsPlaceholderExpansion;
import hexdailyrewards.integration.HexNpcActionBridge;
import hexdailyrewards.storage.YamlRewardStorage;
import org.bukkit.configuration.ConfigurationSection;
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

    private static final int CONFIG_VERSION = 6;

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
        boolean anyAvailable = rewardService.accessibleRewardGroups(player).stream()
                .anyMatch(group -> rewardService.state(player, group.id()).available());
        if (!anyAvailable) {
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
        if (version < 3) {
            migrateRewardGroups(config);
            getLogger().info("Migrated HexDailyRewards reward groups to version 3.");
        }
        if (version < 4) {
            migrateColumnGui(config);
            getLogger().info("Migrated HexDailyRewards GUI to version 4.");
        }
        if (version < 5) {
            migrateHologramStatus(config);
            getLogger().info("Migrated HexDailyRewards hologram status to version 5.");
        }
        if (version < 6) {
            migrateRewardTooltipCosmetics(config);
            getLogger().info("Migrated HexDailyRewards reward tooltip cosmetics to version 6.");
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

    private void migrateRewardGroups(FileConfiguration config) {
        config.set("gui.title", "&cDaily Rewards");
        config.set("gui.size", 45);

        config.set("gui.items.available.slot", 13);
        config.set("gui.items.available.name", "&a{group_name} &8- &f{reward_name}");
        config.set("gui.items.available.lore", List.of(
                "{reward_lore}",
                "",
                "&7Rangi: &f{group_ranks}",
                "&aKliknij, aby odebrac."
        ));

        config.set("gui.items.claimed.slot", 13);
        config.set("gui.items.claimed.name", "&c{group_name} &8- &f{reward_name}");
        config.set("gui.items.claimed.lore", List.of(
                "{reward_lore}",
                "",
                "&7Rangi: &f{group_ranks}",
                "&cOdebrano dzisiaj.",
                "&7Reset: &f{reset_time}"
        ));

        config.set("gui.items.locked.enabled", true);
        config.set("gui.items.locked.slot", 13);
        config.set("gui.items.locked.material", "GRAY_DYE");
        config.set("gui.items.locked.use-reward-material", false);
        config.set("gui.items.locked.name", "&7{group_name}");
        config.set("gui.items.locked.lore", List.of(
                "&cTa kategoria nie jest dostepna",
                "&cdla twojej rangi.",
                "",
                "&7Rangi: &f{group_ranks}"
        ));

        config.set("gui.items.status-available.slot", 31);
        config.set("gui.items.status-claimed.slot", 31);
        config.set("gui.items.info.enabled", true);
        config.set("gui.items.info.slot", 4);
        config.set("gui.items.info.material", "CLOCK");
        config.set("gui.items.info.name", "&6Daily Rewards");
        config.set("gui.items.info.lore", List.of(
                "&7Wybierz skrzynke dostepna",
                "&7dla swojej rangi."
        ));
        config.set("gui.items.close.slot", 40);

        config.set("reward-groups.default.enabled", true);
        config.set("reward-groups.default.display-name", "&aGracze / Media");
        config.set("reward-groups.default.ranks", List.of("default", "media"));
        config.set("reward-groups.default.permissions", List.of(
                "hexdailyrewards.rank.default",
                "hexdailyrewards.rank.media",
                "group.default",
                "group.media"
        ));
        config.set("reward-groups.default.priority", 10);
        config.set("reward-groups.default.fallback-access", true);
        config.set("reward-groups.default.slot", 11);

        config.set("reward-groups.vip.enabled", true);
        config.set("reward-groups.vip.display-name", "&6VIP / SVIP");
        config.set("reward-groups.vip.ranks", List.of("vip", "svip"));
        config.set("reward-groups.vip.permissions", List.of(
                "hexdailyrewards.rank.vip",
                "hexdailyrewards.rank.svip",
                "group.vip",
                "group.svip"
        ));
        config.set("reward-groups.vip.priority", 20);
        config.set("reward-groups.vip.fallback-access", false);
        config.set("reward-groups.vip.slot", 13);

        config.set("reward-groups.elite.enabled", true);
        config.set("reward-groups.elite.display-name", "&dElita");
        config.set("reward-groups.elite.ranks", List.of("elita"));
        config.set("reward-groups.elite.permissions", List.of(
                "hexdailyrewards.rank.elita",
                "hexdailyrewards.rank.elite",
                "group.elita",
                "group.elite"
        ));
        config.set("reward-groups.elite.priority", 30);
        config.set("reward-groups.elite.fallback-access", false);
        config.set("reward-groups.elite.slot", 15);

        ConfigurationSection legacyCalendar = config.getConfigurationSection("rewards-calendar");
        if (legacyCalendar != null) {
            config.set("reward-groups.default.rewards-calendar", null);
            copySection(config, legacyCalendar, "reward-groups.default.rewards-calendar");
        }
    }

    private void migrateColumnGui(FileConfiguration config) {
        config.set("placeholders.hologram-status-available", "&aDo odebrania");
        config.set("placeholders.hologram-status-claimed", "&cOdebrano");

        config.set("reward-groups.default.slot", 19);
        config.set("reward-groups.default.frame-material", "BLACK_STAINED_GLASS_PANE");
        config.set("reward-groups.default.frame-columns", List.of(1, 2, 3));
        config.set("reward-groups.default.frame-name", "");
        config.set("reward-groups.default.frame-lore", List.of());
        config.set("reward-groups.default.frame-hide-tooltip", true);

        config.set("reward-groups.vip.slot", 22);
        config.set("reward-groups.vip.frame-material", "YELLOW_STAINED_GLASS_PANE");
        config.set("reward-groups.vip.frame-columns", List.of(4, 5, 6));
        config.set("reward-groups.vip.frame-name", "");
        config.set("reward-groups.vip.frame-lore", List.of());
        config.set("reward-groups.vip.frame-hide-tooltip", true);

        config.set("reward-groups.elite.slot", 25);
        config.set("reward-groups.elite.frame-material", "LIGHT_BLUE_STAINED_GLASS_PANE");
        config.set("reward-groups.elite.frame-columns", List.of(7, 8, 9));
        config.set("reward-groups.elite.frame-name", "");
        config.set("reward-groups.elite.frame-lore", List.of());
        config.set("reward-groups.elite.frame-hide-tooltip", true);

        config.set("gui.items.available.name", "&a{group_name} &8- &f{reward_name}");
        config.set("gui.items.available.lore", List.of(
                "{reward_lore}",
                "",
                "&7Status: &f{player_status}",
                "&7Reset: &f{reset_time}",
                "&7Rangi: &f{group_ranks}",
                "",
                "&aKliknij, aby odebrac."
        ));

        config.set("gui.items.claimed.name", "&c{group_name} &8- &f{reward_name}");
        config.set("gui.items.claimed.lore", List.of(
                "{reward_lore}",
                "",
                "&7Status: &f{player_status}",
                "&7Reset: &f{reset_time}",
                "&7Do nastepnej nagrody: &f{time}",
                "&7Rangi: &f{group_ranks}"
        ));

        config.set("gui.items.locked.name", "&7{group_name}");
        config.set("gui.items.locked.lore", List.of(
                "&cTa kategoria nie jest dostepna",
                "&cdla twojej rangi.",
                "",
                "&7Rangi: &f{group_ranks}"
        ));

        config.set("gui.items.status-available.enabled", false);
        config.set("gui.items.status-claimed.enabled", false);
        config.set("gui.items.info.enabled", false);
        config.set("gui.items.close.enabled", false);
    }

    private void migrateHologramStatus(FileConfiguration config) {
        config.set("placeholders.player-status-claimed", "&cOdebrano");
        config.set("placeholders.hologram-status-available", "&aDo odebrania");
        config.set("placeholders.hologram-status-claimed", "&cOdebrano");
    }

    private void migrateRewardTooltipCosmetics(FileConfiguration config) {
        List<String> rewardLore = List.of(
                "&fNagroda: {reward_name}",
                "",
                "&fStatus: {player_status}",
                "&fNastepna nagroda za: {time}"
        );
        config.set("gui.items.available.name", "{group_name}");
        config.set("gui.items.available.lore", rewardLore);

        config.set("gui.items.claimed.name", "{group_name}");
        config.set("gui.items.claimed.lore", rewardLore);

        config.set("gui.items.locked.use-reward-material", true);
        config.set("gui.items.locked.name", "{group_name}");
        config.set("gui.items.locked.lore", List.of(
                "&fNagroda: {reward_name}",
                "",
                "&fStatus: &cNiedostepna",
                "&fNastepna nagroda za: -"
        ));
    }

    private void copySection(FileConfiguration config, ConfigurationSection source, String targetPath) {
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key)) {
                continue;
            }
            config.set(targetPath + "." + key, source.get(key));
        }
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
