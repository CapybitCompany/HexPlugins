package hexleszek.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LeszekStorage {

    private final File file;
    private final Logger logger;
    private YamlConfiguration data = new YamlConfiguration();

    public LeszekStorage(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void load() {
        if (!file.exists()) {
            ensureParentDirectory();
            data = new YamlConfiguration();
            save();
            return;
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        ensureParentDirectory();
        try {
            data.save(file);
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Could not save HexLeszek storage: " + file.getAbsolutePath(), ex);
        }
    }

    public boolean hasClaim(UUID playerId) {
        return data.isConfigurationSection(path(playerId));
    }

    public void markClaimed(Player player, Instant now, int rewardAmount, long playtimeSeconds) {
        String path = path(player.getUniqueId());
        data.set(path + ".uuid", player.getUniqueId().toString());
        data.set(path + ".name", player.getName());
        data.set(path + ".first-claim-at", now.toString());
        data.set(path + ".last-seen-at", now.toString());
        data.set(path + ".reward-amount", rewardAmount);
        data.set(path + ".playtime-seconds", playtimeSeconds);
        data.set(path + ".playtime-formatted", formatDuration(playtimeSeconds));
    }

    public void updatePlaytime(Player player, Instant now, long playtimeSeconds) {
        String path = path(player.getUniqueId());
        if (!data.isConfigurationSection(path)) {
            return;
        }
        data.set(path + ".name", player.getName());
        data.set(path + ".last-seen-at", now.toString());
        data.set(path + ".playtime-seconds", playtimeSeconds);
        data.set(path + ".playtime-formatted", formatDuration(playtimeSeconds));
    }

    public long playtimeSeconds(UUID playerId) {
        return data.getLong(path(playerId) + ".playtime-seconds", 0L);
    }

    public int trackedPlayers() {
        var section = data.getConfigurationSection("players");
        return section == null ? 0 : section.getKeys(false).size();
    }

    private void ensureParentDirectory() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            logger.warning("Could not create HexLeszek storage directory: " + parent.getAbsolutePath());
        }
    }

    private String path(UUID playerId) {
        return "players." + playerId;
    }

    private String formatDuration(long totalSeconds) {
        long seconds = Math.max(0L, totalSeconds);
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;
        return days + "d " + hours + "h " + minutes + "m " + seconds + "s";
    }
}
