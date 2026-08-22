package hexcasino.machine;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistent per-player deterministic cycle, calendar-day reward counter and paid game checkpoint. */
public final class SkillSlotPlayerStateStore {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, State> cache = new HashMap<>();
    private final Map<UUID, ActiveGame> activeGames = new HashMap<>();

    public SkillSlotPlayerStateStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "skill-slot-state.yml");
        load();
    }

    public synchronized State state(UUID playerId, SkillSlotSettings settings) {
        State state = cache.computeIfAbsent(playerId, ignored -> new State(1, today(settings.dailyZone()), 0.0D));
        LocalDate today = today(settings.dailyZone());
        if (!today.equals(state.rewardDate())) {
            state = new State(state.nextReelSet(), today, 0.0D);
            cache.put(playerId, state);
            save();
        }
        return state;
    }

    public synchronized int currentNextSet(UUID playerId, SkillSlotSettings settings) {
        return normalize(state(playerId, settings).nextReelSet(), settings.reelSetCount());
    }

    /** Advances the per-player deterministic cycle and creates the paid-game checkpoint in one state mutation/save. */
    public synchronized boolean reserveAndCheckpoint(UUID playerId, SkillSlotSettings settings, ActiveGame game) {
        State old = state(playerId, settings);
        int reserved = normalize(old.nextReelSet(), settings.reelSetCount());
        if (game.reelSetIndex() != reserved) return false;
        int next = reserved >= settings.reelSetCount() ? 1 : reserved + 1;
        cache.put(playerId, new State(next, old.rewardDate(), old.dailyGrossRewards()));
        activeGames.put(playerId, game.copy());
        save();
        return true;
    }

    public synchronized int reserveNextSet(UUID playerId, SkillSlotSettings settings) {
        State old = state(playerId, settings);
        int reserved = normalize(old.nextReelSet(), settings.reelSetCount());
        int next = reserved >= settings.reelSetCount() ? 1 : reserved + 1;
        cache.put(playerId, new State(next, old.rewardDate(), old.dailyGrossRewards()));
        save();
        return reserved;
    }

    public synchronized void addReward(UUID playerId, SkillSlotSettings settings, double amount) {
        State old = state(playerId, settings);
        cache.put(playerId, new State(old.nextReelSet(), old.rewardDate(), old.dailyGrossRewards() + Math.max(0.0D, amount)));
        save();
    }

    public synchronized boolean canStartPaid(UUID playerId, SkillSlotSettings settings) {
        return state(playerId, settings).dailyGrossRewards() + 1.0E-9 < settings.dailyRewardThreshold();
    }

    public synchronized Optional<ActiveGame> activeGame(UUID playerId) {
        ActiveGame game = activeGames.get(playerId);
        return game == null ? Optional.empty() : Optional.of(game.copy());
    }

    public synchronized void checkpoint(UUID playerId, ActiveGame game) {
        activeGames.put(playerId, game.copy());
        save();
    }

    public synchronized void clearActiveGame(UUID playerId) {
        if (activeGames.remove(playerId) != null) save();
    }

    private int normalize(int value, int count) { return Math.floorMod(value - 1, count) + 1; }
    private static LocalDate today(ZoneId zone) { return LocalDate.now(zone); }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var section = yaml.getConfigurationSection("players");
        if (section == null) return;
        for (String rawUuid : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(rawUuid);
                String p = "players." + rawUuid + ".";
                int next = Math.max(1, yaml.getInt(p + "next-reel-set", 1));
                LocalDate date;
                try { date = LocalDate.parse(yaml.getString(p + "reward-date", LocalDate.now().toString())); }
                catch (RuntimeException ex) { date = LocalDate.now(); }
                double rewards = Math.max(0.0D, yaml.getDouble(p + "daily-gross-rewards", 0.0D));
                cache.put(uuid, new State(next, date, rewards));

                if (yaml.getBoolean(p + "active.enabled", false)) {
                    int[] positions = yaml.getIntegerList(p + "active.positions").stream().mapToInt(Integer::intValue).toArray();
                    boolean[] stopped = new boolean[yaml.getBooleanList(p + "active.stopped").size()];
                    var stoppedList = yaml.getBooleanList(p + "active.stopped");
                    for (int i = 0; i < stopped.length; i++) stopped[i] = stoppedList.get(i);
                    if (positions.length > 0 && positions.length == stopped.length) {
                        java.util.List<ResolvedStop> resolvedStops = new java.util.ArrayList<>();
                        var stopSection = yaml.getConfigurationSection(p + "active.resolved-stops");
                        if (stopSection != null) {
                            java.util.List<String> keys = new java.util.ArrayList<>(stopSection.getKeys(false));
                            keys.sort(java.util.Comparator.comparingInt(Integer::parseInt));
                            for (String index : keys) {
                                String sp = p + "active.resolved-stops." + index + ".";
                                resolvedStops.add(new ResolvedStop(
                                        yaml.getInt(sp + "reel-index"),
                                        yaml.getLong(sp + "frame-seq"),
                                        yaml.getInt(sp + "container-state-id"),
                                        yaml.getLong(sp + "frame-start-nano"),
                                        yaml.getLong(sp + "packet-receive-nano"),
                                        yaml.getInt(sp + "resolved-position")
                                ));
                            }
                        }
                        activeGames.put(uuid, new ActiveGame(
                                yaml.getLong(p + "active.game-id"),
                                yaml.getInt(p + "active.reel-set", 1),
                                yaml.getInt(p + "active.layout-reels", 1),
                                yaml.getDouble(p + "active.base-cost", 1.0D),
                                yaml.getString(p + "active.difficulty", "normal"),
                                yaml.getDouble(p + "active.charged-stake", 1.0D),
                                positions, stopped, resolvedStops
                        ));
                    }
                }
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid player UUID in skill-slot-state.yml: " + rawUuid);
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        java.util.Set<UUID> ids = new java.util.HashSet<>(cache.keySet());
        ids.addAll(activeGames.keySet());
        for (UUID uuid : ids) {
            State state = cache.getOrDefault(uuid, new State(1, LocalDate.now(), 0.0D));
            String p = "players." + uuid + ".";
            yaml.set(p + "next-reel-set", state.nextReelSet());
            yaml.set(p + "reward-date", state.rewardDate().toString());
            yaml.set(p + "daily-gross-rewards", state.dailyGrossRewards());
            ActiveGame game = activeGames.get(uuid);
            yaml.set(p + "active.enabled", game != null);
            if (game != null) {
                yaml.set(p + "active.game-id", game.gameId());
                yaml.set(p + "active.reel-set", game.reelSetIndex());
                yaml.set(p + "active.layout-reels", game.layoutReels());
                yaml.set(p + "active.base-cost", game.baseCost());
                yaml.set(p + "active.difficulty", game.difficultyId());
                yaml.set(p + "active.charged-stake", game.chargedStake());
                yaml.set(p + "active.positions", Arrays.stream(game.positions()).boxed().toList());
                java.util.List<Boolean> stopped = new java.util.ArrayList<>();
                for (boolean value : game.stopped()) stopped.add(value);
                yaml.set(p + "active.stopped", stopped);
                int stopIndex = 0;
                for (ResolvedStop stop : game.resolvedStops()) {
                    String sp = p + "active.resolved-stops." + (stopIndex++) + ".";
                    yaml.set(sp + "reel-index", stop.stopUnit());
                    yaml.set(sp + "frame-seq", stop.frameSeq());
                    yaml.set(sp + "container-state-id", stop.containerStateId());
                    yaml.set(sp + "frame-start-nano", stop.frameStartNano());
                    yaml.set(sp + "packet-receive-nano", stop.packetReceiveNano());
                    yaml.set(sp + "resolved-position", stop.resolvedPosition());
                }
            }
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Unable to save skill-slot-state.yml: " + ex.getMessage());
        }
    }

    public record State(int nextReelSet, LocalDate rewardDate, double dailyGrossRewards) {}

    public record ActiveGame(long gameId, int reelSetIndex, int layoutReels, double baseCost,
                             String difficultyId, double chargedStake, int[] positions, boolean[] stopped,
                             java.util.List<ResolvedStop> resolvedStops) {
        public ActiveGame {
            positions = Arrays.copyOf(positions, positions.length);
            stopped = Arrays.copyOf(stopped, stopped.length);
            resolvedStops = java.util.List.copyOf(resolvedStops);
        }
        @Override public int[] positions() { return Arrays.copyOf(positions, positions.length); }
        @Override public boolean[] stopped() { return Arrays.copyOf(stopped, stopped.length); }
        public ActiveGame copy() { return new ActiveGame(gameId, reelSetIndex, layoutReels, baseCost, difficultyId, chargedStake, positions, stopped, resolvedStops); }
    }
}
