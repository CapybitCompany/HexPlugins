package hexdailyrewards.storage;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

public final class YamlRewardStorage implements RewardStorage {

    private final File file;
    private YamlConfiguration config;

    public YamlRewardStorage(File file) {
        this.file = file;
    }

    public synchronized void load() {
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            throw new IllegalStateException("Cannot create data folder: " + file.getParentFile());
        }
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    @Override
    public synchronized Optional<LocalDate> lastClaimDate(UUID playerId) {
        ensureLoaded();
        String raw = config.getString(path(playerId) + ".last-claim-date");
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(raw));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public synchronized void markClaimed(UUID playerId, String playerName, LocalDate claimDate, Instant claimedAt) throws IOException {
        ensureLoaded();
        String path = path(playerId);
        config.set(path + ".name", playerName);
        config.set(path + ".last-claim-date", claimDate.toString());
        config.set(path + ".last-claim-at", claimedAt.toString());
        config.save(file);
    }

    private String path(UUID playerId) {
        return "players." + playerId;
    }

    private void ensureLoaded() {
        if (config == null) {
            load();
        }
    }
}

