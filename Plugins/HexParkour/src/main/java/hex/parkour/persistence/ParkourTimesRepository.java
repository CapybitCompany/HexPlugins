package hex.parkour.persistence;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ParkourTimesRepository {
    private final Plugin plugin;
    private final File file;
    private final Map<UUID, Map<String, ParkourTimeRecord>> recordsByPlayer = new LinkedHashMap<>();
    private final Map<String, List<ParkourTimeRecord>> topByArena = new HashMap<>();
    private final Map<String, Map<String, ParkourTimeRecord>> recordsByName = new HashMap<>();
    private boolean available;

    public ParkourTimesRepository(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "times.yml");
    }

    public synchronized void initialize() {
        plugin.getDataFolder().mkdirs();
        recordsByPlayer.clear();
        topByArena.clear();
        recordsByName.clear();
        try {
            if (!file.exists()) {
                save();
            } else {
                load();
            }
            rebuildIndexes();
            available = true;
        } catch (Throwable error) {
            available = false;
            plugin.getLogger().severe("HexParkour times.yml storage is unavailable: " + rootMessage(error));
        }
    }

    public synchronized boolean available() {
        return available;
    }

    public synchronized RecordUpdate recordFinish(UUID playerId, String playerName, String arenaId, long timeMillis) {
        ensureAvailable();
        long safeTimeMillis = Math.max(0L, timeMillis);
        String safeName = sanitizeName(playerName);
        Map<String, ParkourTimeRecord> byArena = recordsByPlayer.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>());
        ParkourTimeRecord previous = byArena.get(arenaId);
        ParkourTimeRecord updated = previous == null
                ? new ParkourTimeRecord(playerId, safeName, arenaId, safeTimeMillis, safeTimeMillis, 1, System.currentTimeMillis(), false)
                : previous.withFinish(safeTimeMillis, safeName, System.currentTimeMillis());
        boolean newBest = previous == null || safeTimeMillis < previous.bestTimeMillis();
        putAndPersist(playerId, arenaId, previous, updated);
        return new RecordUpdate(updated, newBest);
    }

    public synchronized boolean markFinishRewardClaimed(UUID playerId, String playerName, String arenaId) {
        ensureAvailable();
        Map<String, ParkourTimeRecord> byArena = recordsByPlayer.get(playerId);
        if (byArena == null) return false;
        ParkourTimeRecord previous = byArena.get(arenaId);
        if (previous == null || previous.finishRewardClaimed()) return false;
        ParkourTimeRecord updated = previous.withFinishRewardClaimed(sanitizeName(playerName), System.currentTimeMillis());
        putAndPersist(playerId, arenaId, previous, updated);
        return true;
    }

    public synchronized Optional<ParkourTimeRecord> find(UUID playerId, String arenaId) {
        Map<String, ParkourTimeRecord> byArena = recordsByPlayer.get(playerId);
        return byArena == null ? Optional.empty() : Optional.ofNullable(byArena.get(arenaId));
    }

    public synchronized Optional<ParkourTimeRecord> findByPlayerName(String playerName, String arenaId) {
        Map<String, ParkourTimeRecord> byArena = recordsByName.get(normalizeName(playerName));
        return byArena == null ? Optional.empty() : Optional.ofNullable(byArena.get(arenaId));
    }

    public synchronized List<ParkourTimeRecord> top(String arenaId, int limit) {
        List<ParkourTimeRecord> records = topByArena.getOrDefault(arenaId, List.of());
        return records.stream().limit(Math.max(0, limit)).toList();
    }

    private void putAndPersist(UUID playerId, String arenaId, ParkourTimeRecord previous, ParkourTimeRecord updated) {
        Map<String, ParkourTimeRecord> byArena = recordsByPlayer.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>());
        byArena.put(arenaId, updated);
        try {
            save();
            rebuildIndexes();
        } catch (IOException error) {
            if (previous == null) {
                byArena.remove(arenaId);
                if (byArena.isEmpty()) recordsByPlayer.remove(playerId);
            } else {
                byArena.put(arenaId, previous);
            }
            rebuildIndexes();
            throw new IllegalStateException("Could not save parkour times.yml", error);
        }
    }

    private void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return;
        for (String uuidText : players.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(uuidText);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid player UUID in times.yml: " + uuidText);
                continue;
            }
            ConfigurationSection playerSection = players.getConfigurationSection(uuidText);
            if (playerSection == null) continue;
            String name = sanitizeName(playerSection.getString("name", "-"));
            ConfigurationSection arenas = playerSection.getConfigurationSection("arenas");
            if (arenas == null) continue;
            Map<String, ParkourTimeRecord> byArena = new LinkedHashMap<>();
            for (String arenaId : arenas.getKeys(false)) {
                ConfigurationSection arenaSection = arenas.getConfigurationSection(arenaId);
                if (arenaSection == null) continue;
                long best = arenaSection.getLong("best-time-millis", -1L);
                long last = arenaSection.getLong("last-time-millis", best);
                int finishes = Math.max(0, arenaSection.getInt("finishes", best >= 0L ? 1 : 0));
                long updatedAt = arenaSection.getLong("updated-at", 0L);
                boolean rewardClaimed = arenaSection.getBoolean("finish-reward-claimed", false);
                if (best < 0L) continue;
                byArena.put(arenaId, new ParkourTimeRecord(
                        playerId,
                        name,
                        arenaId,
                        best,
                        Math.max(0L, last),
                        finishes,
                        updatedAt,
                        rewardClaimed
                ));
            }
            if (!byArena.isEmpty()) recordsByPlayer.put(playerId, byArena);
        }
    }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().header("HexParkour player times and one-time finish reward claims.");
        for (Map.Entry<UUID, Map<String, ParkourTimeRecord>> playerEntry : recordsByPlayer.entrySet()) {
            UUID playerId = playerEntry.getKey();
            String playerPath = "players." + playerId;
            String name = playerEntry.getValue().values().stream()
                    .max(Comparator.comparingLong(ParkourTimeRecord::updatedAtMillis))
                    .map(ParkourTimeRecord::playerName)
                    .orElse("-");
            yaml.set(playerPath + ".name", name);
            for (ParkourTimeRecord record : playerEntry.getValue().values()) {
                String arenaPath = playerPath + ".arenas." + record.arenaId();
                yaml.set(arenaPath + ".best-time-millis", record.bestTimeMillis());
                yaml.set(arenaPath + ".last-time-millis", record.lastTimeMillis());
                yaml.set(arenaPath + ".finishes", record.finishes());
                yaml.set(arenaPath + ".updated-at", record.updatedAtMillis());
                yaml.set(arenaPath + ".finish-reward-claimed", record.finishRewardClaimed());
            }
        }

        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        File temp = new File(parent, file.getName() + ".tmp");
        yaml.save(temp);
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void rebuildIndexes() {
        topByArena.clear();
        recordsByName.clear();
        for (Map<String, ParkourTimeRecord> byArena : recordsByPlayer.values()) {
            for (ParkourTimeRecord record : byArena.values()) {
                topByArena.computeIfAbsent(record.arenaId(), ignored -> new ArrayList<>()).add(record);
                recordsByName
                        .computeIfAbsent(normalizeName(record.playerName()), ignored -> new HashMap<>())
                        .merge(record.arenaId(), record, ParkourTimesRepository::bestOf);
            }
        }
        Comparator<ParkourTimeRecord> comparator = Comparator
                .comparingLong(ParkourTimeRecord::bestTimeMillis)
                .thenComparing(ParkourTimeRecord::updatedAtMillis)
                .thenComparing(record -> record.playerName().toLowerCase(Locale.ROOT));
        for (Map.Entry<String, List<ParkourTimeRecord>> entry : topByArena.entrySet()) {
            entry.setValue(entry.getValue().stream().sorted(comparator).toList());
        }
    }

    private static ParkourTimeRecord bestOf(ParkourTimeRecord left, ParkourTimeRecord right) {
        if (left.bestTimeMillis() != right.bestTimeMillis()) {
            return left.bestTimeMillis() < right.bestTimeMillis() ? left : right;
        }
        return left.updatedAtMillis() <= right.updatedAtMillis() ? left : right;
    }

    private static String sanitizeName(String playerName) {
        if (playerName == null || playerName.isBlank()) return "-";
        return playerName.replaceAll("[^A-Za-z0-9_]", "");
    }

    private static String normalizeName(String playerName) {
        return sanitizeName(playerName).toLowerCase(Locale.ROOT);
    }

    private void ensureAvailable() {
        if (!available) throw new IllegalStateException("Parkour times.yml storage is not available");
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record RecordUpdate(ParkourTimeRecord record, boolean newBest) {
    }
}
