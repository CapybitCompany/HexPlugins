package hexcasino.machine;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent deterministic replay/audit log for paid Reel Challenge games.
 *
 * The log intentionally stores enough information to recompute a result without consulting the
 * animation or current GUI state. It is not used to choose outcomes.
 */
public final class SkillSlotAuditStore {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<Long, AuditRecord> records = new LinkedHashMap<>();

    public SkillSlotAuditStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "reel-challenge-audit.yml");
        load();
    }

    public synchronized void record(AuditRecord record) {
        records.put(record.gameId(), record.copy());
        save();
    }

    public synchronized Optional<AuditRecord> find(long gameId) {
        AuditRecord record = records.get(gameId);
        return record == null ? Optional.empty() : Optional.of(record.copy());
    }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var section = yaml.getConfigurationSection("games");
        if (section == null) return;
        for (String rawId : section.getKeys(false)) {
            try {
                long gameId = Long.parseLong(rawId);
                String p = "games." + rawId + ".";
                UUID playerId = UUID.fromString(yaml.getString(p + "player", ""));
                int[] positions = yaml.getIntegerList(p + "positions").stream().mapToInt(Integer::intValue).toArray();
                List<ResolvedStop> stops = new ArrayList<>();
                var stopSection = yaml.getConfigurationSection(p + "stops");
                if (stopSection != null) {
                    for (String index : stopSection.getKeys(false)) {
                        String sp = p + "stops." + index + ".";
                        stops.add(new ResolvedStop(
                                yaml.getInt(sp + "reel-index"),
                                yaml.getLong(sp + "frame-seq"),
                                yaml.getInt(sp + "container-state-id"),
                                yaml.getLong(sp + "frame-start-nano"),
                                yaml.getLong(sp + "packet-receive-nano"),
                                yaml.getInt(sp + "resolved-position")
                        ));
                    }
                }
                Map<String, Double> rewards = new LinkedHashMap<>();
                var rewardSection = yaml.getConfigurationSection(p + "rewards");
                if (rewardSection != null) {
                    for (String key : rewardSection.getKeys(false)) rewards.put(key, yaml.getDouble(p + "rewards." + key));
                }
                LocalDate date;
                try { date = LocalDate.parse(yaml.getString(p + "daily-date", LocalDate.now().toString())); }
                catch (RuntimeException ex) { date = LocalDate.now(); }
                records.put(gameId, new AuditRecord(
                        gameId,
                        playerId,
                        yaml.getString(p + "reel-set-version", "unknown"),
                        yaml.getInt(p + "reel-set-index"),
                        yaml.getInt(p + "layout-reels"),
                        yaml.getDouble(p + "base-cost"),
                        yaml.getString(p + "difficulty", "unknown"),
                        yaml.getInt(p + "frame-ms"),
                        yaml.getDouble(p + "charged-stake"),
                        positions,
                        stops,
                        rewards,
                        yaml.getInt(p + "winning-patterns"),
                        yaml.getDouble(p + "gross-payout"),
                        yaml.getBoolean(p + "reward-dispatched", true),
                        date,
                        yaml.getDouble(p + "daily-before"),
                        yaml.getDouble(p + "daily-after"),
                        yaml.getBoolean(p + "locked-after")
                ));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Ignoring invalid Reel Challenge audit record " + rawId + ": " + ex.getMessage());
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (AuditRecord record : records.values()) {
            String p = "games." + record.gameId() + ".";
            yaml.set(p + "player", record.playerId().toString());
            yaml.set(p + "reel-set-version", record.reelSetVersion());
            yaml.set(p + "reel-set-index", record.reelSetIndex());
            yaml.set(p + "layout-reels", record.layoutReels());
            yaml.set(p + "base-cost", record.baseCost());
            yaml.set(p + "difficulty", record.difficultyId());
            yaml.set(p + "frame-ms", record.frameMs());
            yaml.set(p + "charged-stake", record.chargedStake());
            yaml.set(p + "positions", Arrays.stream(record.positions()).boxed().toList());
            int i = 0;
            for (ResolvedStop stop : record.stops()) {
                String sp = p + "stops." + (i++) + ".";
                yaml.set(sp + "reel-index", stop.stopUnit());
                yaml.set(sp + "frame-seq", stop.frameSeq());
                yaml.set(sp + "container-state-id", stop.containerStateId());
                yaml.set(sp + "frame-start-nano", stop.frameStartNano());
                yaml.set(sp + "packet-receive-nano", stop.packetReceiveNano());
                yaml.set(sp + "resolved-position", stop.resolvedPosition());
            }
            for (Map.Entry<String, Double> entry : record.rewards().entrySet()) {
                yaml.set(p + "rewards." + entry.getKey(), entry.getValue());
            }
            yaml.set(p + "winning-patterns", record.winningPatterns());
            yaml.set(p + "gross-payout", record.grossPayout());
            yaml.set(p + "reward-dispatched", record.rewardDispatched());
            yaml.set(p + "daily-date", record.dailyDate().toString());
            yaml.set(p + "daily-before", record.dailyBefore());
            yaml.set(p + "daily-after", record.dailyAfter());
            yaml.set(p + "locked-after", record.lockedAfter());
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Unable to save reel-challenge-audit.yml: " + ex.getMessage());
        }
    }

    public record AuditRecord(
            long gameId,
            UUID playerId,
            String reelSetVersion,
            int reelSetIndex,
            int layoutReels,
            double baseCost,
            String difficultyId,
            int frameMs,
            double chargedStake,
            int[] positions,
            List<ResolvedStop> stops,
            Map<String, Double> rewards,
            int winningPatterns,
            double grossPayout,
            boolean rewardDispatched,
            LocalDate dailyDate,
            double dailyBefore,
            double dailyAfter,
            boolean lockedAfter
    ) {
        public AuditRecord {
            positions = Arrays.copyOf(positions, positions.length);
            stops = List.copyOf(stops);
            rewards = Map.copyOf(rewards);
        }
        @Override public int[] positions() { return Arrays.copyOf(positions, positions.length); }
        public AuditRecord copy() {
            return new AuditRecord(gameId, playerId, reelSetVersion, reelSetIndex, layoutReels, baseCost,
                    difficultyId, frameMs, chargedStake, positions, stops, rewards, winningPatterns, grossPayout,
                    rewardDispatched, dailyDate, dailyBefore, dailyAfter, lockedAfter);
        }
    }

    public record Verification(boolean ok, String message, double recomputedPayout, int recomputedPatterns) { }
}
