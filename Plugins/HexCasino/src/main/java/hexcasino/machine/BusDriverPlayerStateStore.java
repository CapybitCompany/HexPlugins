package hexcasino.machine;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistent per-player deterministic board cycle and active paid-game checkpoint. */
public final class BusDriverPlayerStateStore {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Integer> nextBoards = new HashMap<>();
    private final Map<UUID, ActiveGame> activeGames = new HashMap<>();

    public BusDriverPlayerStateStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "busdriver-state.yml");
        load();
    }

    public synchronized int nextBoard(UUID playerId, int boardCount) {
        return normalize(nextBoards.getOrDefault(playerId, 1), boardCount);
    }

    public synchronized Optional<ActiveGame> activeGame(UUID playerId) {
        ActiveGame game = activeGames.get(playerId);
        return game == null ? Optional.empty() : Optional.of(game);
    }

    /** Reserve the current next board without advancing the cycle yet. */
    public synchronized boolean reserve(UUID playerId, int boardCount, ActiveGame game) {
        if (activeGames.containsKey(playerId)) return false;
        int expected = nextBoard(playerId, boardCount);
        if (game.boardIndex() != expected) return false;
        activeGames.put(playerId, game);
        save();
        return true;
    }

    public synchronized void checkpoint(UUID playerId, ActiveGame game) {
        activeGames.put(playerId, game);
        save();
    }

    /** Terminal non-technical result: consume the board and advance 1 -> 100 -> 1. */
    public synchronized void complete(UUID playerId, int boardCount) {
        ActiveGame active = activeGames.remove(playerId);
        if (active != null) {
            int next = active.boardIndex() >= boardCount ? 1 : active.boardIndex() + 1;
            nextBoards.put(playerId, next);
            save();
        }
    }

    /** Technical void: preserve the same next board. */
    public synchronized void technicalVoid(UUID playerId) {
        if (activeGames.remove(playerId) != null) save();
    }

    private int normalize(int value, int count) {
        return Math.floorMod(value - 1, count) + 1;
    }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var section = yaml.getConfigurationSection("players");
        if (section == null) return;
        for (String raw : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(raw);
                String p = "players." + raw + ".";
                nextBoards.put(uuid, Math.max(1, yaml.getInt(p + "next-board", 1)));
                if (yaml.getBoolean(p + "active.enabled", false)) {
                    activeGames.put(uuid, new ActiveGame(
                            yaml.getLong(p + "active.game-id"),
                            yaml.getInt(p + "active.board-index", 1),
                            yaml.getInt(p + "active.stage-index", 0),
                            yaml.getInt(p + "active.completed-rounds", 0),
                            yaml.getInt(p + "active.bet-index", 0),
                            yaml.getDouble(p + "active.stake", 0.0D),
                            yaml.getDouble(p + "active.current-win", 0.0D),
                            yaml.getLong(p + "active.remaining-ms", 3000L),
                            yaml.getString(p + "active.machine-id", "")
                    ));
                }
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Ignoring invalid UUID in busdriver-state.yml: " + raw);
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        java.util.Set<UUID> ids = new java.util.HashSet<>(nextBoards.keySet());
        ids.addAll(activeGames.keySet());
        for (UUID uuid : ids) {
            String p = "players." + uuid + ".";
            yaml.set(p + "next-board", nextBoards.getOrDefault(uuid, 1));
            ActiveGame g = activeGames.get(uuid);
            yaml.set(p + "active.enabled", g != null);
            if (g != null) {
                yaml.set(p + "active.game-id", g.gameId());
                yaml.set(p + "active.board-index", g.boardIndex());
                yaml.set(p + "active.stage-index", g.stageIndex());
                yaml.set(p + "active.completed-rounds", g.completedRounds());
                yaml.set(p + "active.bet-index", g.betIndex());
                yaml.set(p + "active.stake", g.stake());
                yaml.set(p + "active.current-win", g.currentWin());
                yaml.set(p + "active.remaining-ms", g.remainingMs());
                yaml.set(p + "active.machine-id", g.machineId());
            }
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Unable to save busdriver-state.yml: " + ex.getMessage());
        }
    }

    public record ActiveGame(long gameId, int boardIndex, int stageIndex, int completedRounds, int betIndex,
                             double stake, double currentWin, long remainingMs, String machineId) {}
}
