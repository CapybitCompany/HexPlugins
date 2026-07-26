package hexmobplaceholder;

import hexmobplaceholder.command.HexMobPlaceholderCommand;
import hexmobplaceholder.config.MobPlaceholderConfig;
import hexmobplaceholder.config.MobPlaceholderConfigLoader;
import hexmobplaceholder.integration.HexMobPlaceholderExpansion;
import hexmobplaceholder.storage.MobBaselineStorage;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class HexMobPlaceholderPlugin extends JavaPlugin implements Listener {

    private final MobPlaceholderConfigLoader configLoader = new MobPlaceholderConfigLoader();
    private final AtomicReference<MobPlaceholderConfig> configRef = new AtomicReference<>();

    private MobBaselineStorage storage;
    private MobKillCounter counter;
    private Object placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.storage = new MobBaselineStorage(new File(getDataFolder(), "data.yml"));
        if (!storage.load(getLogger())) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!reloadPluginConfig()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.counter = new MobKillCounter(storage, configRef::get);

        HexMobPlaceholderCommand command = new HexMobPlaceholderCommand(this);
        var pluginCommand = getCommand("hexmobplaceholder");
        if (pluginCommand == null) {
            getLogger().severe("Command 'hexmobplaceholder' missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        getServer().getPluginManager().registerEvents(this, this);
        registerPlaceholderExpansion();
        getServer().getScheduler().runTask(this, this::registerPlaceholderExpansion);

        getLogger().info("HexMobPlaceholder enabled.");
    }

    @Override
    public void onDisable() {
        unregisterPlaceholderExpansion();
        if (storage != null) {
            storage.save(getLogger());
        }
        counter = null;
        storage = null;
        getLogger().info("HexMobPlaceholder disabled.");
    }

    public boolean reloadPluginRuntime() {
        reloadConfig();
        if (storage != null && !storage.load(getLogger())) {
            return false;
        }
        boolean loaded = reloadPluginConfig();
        if (loaded) {
            registerPlaceholderExpansion();
        }
        return loaded;
    }

    public MobPlaceholderConfig config() {
        return configRef.get();
    }

    public MobKillCounter counter() {
        return counter;
    }

    public boolean saveData() {
        return storage == null || storage.save(getLogger());
    }

    public List<OfflinePlayer> knownPlayers() {
        Map<UUID, OfflinePlayer> players = new LinkedHashMap<>();
        for (OfflinePlayer player : getServer().getOfflinePlayers()) {
            players.put(player.getUniqueId(), player);
        }
        for (Player player : getServer().getOnlinePlayers()) {
            players.put(player.getUniqueId(), player);
        }
        return List.copyOf(players.values());
    }

    public Optional<OfflinePlayer> findKnownPlayer(String input) {
        Player onlinePlayer = getServer().getPlayerExact(input);
        if (onlinePlayer != null) {
            return Optional.of(onlinePlayer);
        }

        UUID inputUuid = parseUuid(input);
        for (OfflinePlayer player : knownPlayers()) {
            if (inputUuid != null && player.getUniqueId().equals(inputUuid)) {
                return Optional.of(player);
            }

            String name = player.getName();
            if (name != null && name.equalsIgnoreCase(input)) {
                return Optional.of(player);
            }
        }

        if (inputUuid != null) {
            OfflinePlayer uuidPlayer = getServer().getOfflinePlayer(inputUuid);
            if (uuidPlayer.hasPlayedBefore() || uuidPlayer.isOnline()) {
                return Optional.of(uuidPlayer);
            }
        }

        return Optional.empty();
    }

    public boolean isPlaceholderApiEnabled() {
        return getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    public boolean isPlaceholderExpansionRegistered() {
        return placeholderExpansion != null;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("PlaceholderAPI")) {
            registerPlaceholderExpansion();
        }
    }

    private boolean reloadPluginConfig() {
        try {
            configRef.set(configLoader.load(getConfig(), getLogger()));
            return true;
        } catch (RuntimeException ex) {
            getLogger().severe("Failed to load HexMobPlaceholder config: " + ex.getMessage());
            return false;
        }
    }

    private UUID parseUuid(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void registerPlaceholderExpansion() {
        if (placeholderExpansion != null) {
            return;
        }
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().info("PlaceholderAPI not detected. HexMobPlaceholder placeholders are disabled.");
            return;
        }
        try {
            HexMobPlaceholderExpansion expansion = new HexMobPlaceholderExpansion(this);
            if (expansion.register()) {
                placeholderExpansion = expansion;
                getLogger().info("Registered PlaceholderAPI placeholders.");
            } else {
                getLogger().warning("Failed to register PlaceholderAPI placeholders.");
            }
        } catch (NoClassDefFoundError ex) {
            getLogger().info("PlaceholderAPI classes are unavailable. HexMobPlaceholder placeholders are disabled.");
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
