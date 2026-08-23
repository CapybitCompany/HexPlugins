package hexleszek;

import hexleszek.command.LeszekCommand;
import hexleszek.listener.LeszekPlayerListener;
import hexleszek.storage.LeszekStorage;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

public class HexLeszekPlugin extends JavaPlugin {

    private Clock clock = Clock.systemUTC();
    private LeszekStorage storage;
    private LeszekPlayerListener listener;
    private BukkitTask trackingTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.storage = new LeszekStorage(new File(getDataFolder(), getConfig().getString("storage.file", "players.yml")), getLogger());
        storage.load();

        LeszekCommand command = new LeszekCommand(this);
        var pluginCommand = getCommand("leszek");
        if (pluginCommand == null) {
            getLogger().severe("Command 'leszek' missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        pluginCommand.setExecutor(command);

        this.listener = new LeszekPlayerListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        startTrackingTask();

        getLogger().info("HexLeszek enabled.");
    }

    @Override
    public void onDisable() {
        if (trackingTask != null) {
            trackingTask.cancel();
            trackingTask = null;
        }
        updateOnlineTrackedPlayers();
        if (storage != null) {
            storage.save();
            storage = null;
        }
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        getLogger().info("HexLeszek disabled.");
    }

    public boolean claim(Player player) {
        if (!getConfig().getBoolean("enabled", true)) {
            player.sendMessage(Text.component(message("disabled")));
            return true;
        }
        if (!player.hasPermission("hexleszek.use")) {
            player.sendMessage(Text.component(message("no-permission")));
            return true;
        }

        updateTrackedPlayer(player);
        if (storage.hasClaim(player.getUniqueId())) {
            player.sendMessage(Text.component(message("already-claimed")));
            return true;
        }

        int amount = getConfig().getInt("reward.amount", 30);
        String rewardCommand = Text.apply(getConfig().getString("reward.command", "hexeconomy add {player} {amount}"), Map.of(
                "player", player.getName(),
                "uuid", player.getUniqueId().toString(),
                "amount", Integer.toString(amount)
        ));
        if (!getServer().dispatchCommand(getServer().getConsoleSender(), rewardCommand)) {
            getLogger().warning("Reward command failed for " + player.getName() + ": " + rewardCommand);
            player.sendMessage(Text.component(message("reward-failed")));
            return true;
        }

        storage.markClaimed(player, Instant.now(clock), amount, totalPlaytimeSeconds(player));
        storage.save();
        player.sendMessage(Text.component(message("reward-success")));
        return true;
    }

    public void updateTrackedPlayer(Player player) {
        if (storage != null && storage.hasClaim(player.getUniqueId())) {
            storage.updatePlaytime(player, Instant.now(clock), totalPlaytimeSeconds(player));
        }
    }

    public void updateOnlineTrackedPlayers() {
        if (storage == null) {
            return;
        }
        for (Player player : getServer().getOnlinePlayers()) {
            updateTrackedPlayer(player);
        }
        storage.save();
    }

    public LeszekStorage storage() {
        return storage;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private void startTrackingTask() {
        long intervalSeconds = Math.max(30L, getConfig().getLong("tracking.update-interval-seconds", 300L));
        this.trackingTask = getServer().getScheduler().runTaskTimer(this, this::updateOnlineTrackedPlayers,
                intervalSeconds * 20L, intervalSeconds * 20L);
    }

    private long totalPlaytimeSeconds(Player player) {
        return Math.max(0L, player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L);
    }

    private String message(String key) {
        return getConfig().getString("messages." + key, "");
    }
}
