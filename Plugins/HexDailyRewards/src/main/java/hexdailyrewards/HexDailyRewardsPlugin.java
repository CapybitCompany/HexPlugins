package hexdailyrewards;

import hexdailyrewards.command.HexDailyRewardsCommand;
import hexdailyrewards.config.DailyRewardsConfig;
import hexdailyrewards.config.DailyRewardsConfigLoader;
import hexdailyrewards.gui.DailyRewardsGui;
import hexdailyrewards.gui.DailyRewardsInventoryListener;
import hexdailyrewards.integration.HexNpcActionBridge;
import hexdailyrewards.storage.YamlRewardStorage;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class HexDailyRewardsPlugin extends JavaPlugin {

    private final DailyRewardsConfigLoader configLoader = new DailyRewardsConfigLoader();
    private final AtomicReference<DailyRewardsConfig> configRef = new AtomicReference<>();

    private YamlRewardStorage storage;
    private DailyRewardService rewardService;
    private DailyRewardsGui gui;
    private DailyRewardsInventoryListener inventoryListener;
    private HexNpcActionBridge hexNpcActionBridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();

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
        gui = null;
        rewardService = null;
        storage = null;
        getLogger().info("HexDailyRewards disabled.");
    }

    public boolean reloadPluginRuntime() {
        reloadConfig();
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
}
