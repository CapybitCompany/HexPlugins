package hexcasino.machine;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Settings dedicated to deterministic Skill Reel; independent from legacy slot RNG config. */
public record SkillSlotSettings(
        List<Double> baseCosts,
        List<SlotDifficulty> difficulties,
        String defaultDifficulty,
        Map<String, Double> rewards,
        double fiveByThreePayoutMultiplier,
        String reelSetVersion,
        int reelSetCount,
        double dailyRewardThreshold,
        ZoneId dailyZone,
        int snapshotHistoryMs,
        int maxResolvableDelayMs,
        boolean requirePacketStateId,
        boolean failClosedOnUnmappedState,
        boolean practiceEnabled,
        boolean allowPracticeSetSelection,
        boolean paidModeEnabled,
        boolean requireHumanSkillValidation,
        boolean humanSkillValidated,
        boolean requireVisualStateValidation,
        boolean visualStateValidated,
        boolean requirePacketLagValidation,
        boolean packetLagValidated,
        boolean requireValueFlowReview,
        boolean valueFlowReviewed,
        boolean requireUiReview,
        boolean uiReviewed
) {
    public static SkillSlotSettings load(FileConfiguration config) {
        String root = "slot-skill-machine.";
        // Five production variants. 250 ms and 200 ms remain the endpoints; the three
        // middle values are evenly interpolated and rounded to whole milliseconds.
        // A neutral base cost keeps one clickable variant selector responsible for both price and speed.
        List<Double> costs = List.of(1.0D);

        List<SlotDifficulty> difficulties = new ArrayList<>();
        difficulties.add(difficulty(config, root + "difficulty-levels.normal", "normal", "1$", 1, 250, true));
        difficulties.add(difficulty(config, root + "difficulty-levels.low-mid", "low-mid", "10$", 10, 238, true));
        difficulties.add(difficulty(config, root + "difficulty-levels.hard", "hard", "20$", 20, 225, true));
        difficulties.add(difficulty(config, root + "difficulty-levels.high-mid", "high-mid", "50$", 50, 213, true));
        difficulties.add(difficulty(config, root + "difficulty-levels.expert", "expert", "100$", 100, 200, true));

        Map<String, Double> rewards = new LinkedHashMap<>();
        rewards.put("flint", config.getDouble(root + "rewards.flint", 1.00D));
        rewards.put("melon_slice", config.getDouble(root + "rewards.melon_slice", 1.50D));
        rewards.put("gold_nugget", config.getDouble(root + "rewards.gold_nugget", 2.00D));
        rewards.put("blaze_powder", config.getDouble(root + "rewards.blaze_powder", 3.00D));
        rewards.put("amethyst_shard", config.getDouble(root + "rewards.amethyst_shard", 4.00D));
        rewards.put("emerald", config.getDouble(root + "rewards.emerald", 6.00D));
        rewards.put("diamond", config.getDouble(root + "rewards.diamond", 10.00D));
        rewards.put("nether_star", config.getDouble(root + "rewards.nether_star", 16.00D));
        rewards.replaceAll((key, value) -> Math.max(0.0D, value));

        ZoneId zone;
        try {
            zone = ZoneId.of(config.getString(root + "daily-limits.timezone", "Europe/Warsaw"));
        } catch (RuntimeException ex) {
            zone = ZoneId.of("Europe/Warsaw");
        }

        return new SkillSlotSettings(
                List.copyOf(costs), List.copyOf(difficulties),
                config.getString(root + "difficulty-levels.default", "normal"),
                Map.copyOf(rewards),
                Math.max(0.0D, config.getDouble(root + "layout-payout-multipliers.5x3", 4.0D)),
                config.getString(root + "deterministic.reel-set-version", "v1"),
                Math.max(1, config.getInt(root + "deterministic.reel-set-count", 100)),
                Math.max(0.0D, config.getDouble(root + "daily-limits.rewards-stop-threshold", 500.0D)),
                zone,
                Math.max(1000, config.getInt(root + "input-resolution.snapshot-history-ms", 5000)),
                Math.max(1000, config.getInt(root + "input-resolution.max-resolvable-delay-ms", 2000)),
                config.getBoolean(root + "input-resolution.require-packet-state-id", true),
                config.getBoolean(root + "input-resolution.fail-closed-on-unmapped-state", true),
                config.getBoolean(root + "practice.enabled", false),
                config.getBoolean(root + "practice.allow-set-selection", false),
                config.getBoolean(root + "rewards-mode.enabled", true),
                config.getBoolean(root + "production-gates.require-human-skill-validation", true),
                config.getBoolean(root + "production-gates.human-skill-validated", false),
                config.getBoolean(root + "production-gates.require-visual-stateid-validation", true),
                config.getBoolean(root + "production-gates.visual-stateid-validated", false),
                config.getBoolean(root + "production-gates.require-packet-lag-validation", true),
                config.getBoolean(root + "production-gates.packet-lag-validated", false),
                config.getBoolean(root + "production-gates.require-value-flow-review", true),
                config.getBoolean(root + "production-gates.value-flow-reviewed", false),
                config.getBoolean(root + "production-gates.require-ui-review", true),
                config.getBoolean(root + "production-gates.ui-reviewed", false)
        );
    }

    private static SlotDifficulty difficulty(FileConfiguration config, String path, String id, String name,
                                             int multiplier, int frameMs, boolean enabled) {
        // Cost/speed pairs are a gameplay invariant in this release, not a legacy-config override.
        // This also migrates existing v3.1 configs where Expert was still x3/disabled.
        return new SlotDifficulty(
                id,
                config.getString(path + ".display-name", name),
                multiplier,
                frameMs,
                enabled
        );
    }

    public SlotDifficulty difficulty(String id) {
        return difficulties.stream().filter(it -> it.id().equalsIgnoreCase(id)).findFirst().orElse(difficulties.getFirst());
    }

    public int difficultyIndex(String id) {
        for (int i = 0; i < difficulties.size(); i++) if (difficulties.get(i).id().equalsIgnoreCase(id)) return i;
        return 0;
    }

    public Map<String, Double> rewardsForLayout(int reels) {
        if (reels != 5 || Math.abs(fiveByThreePayoutMultiplier - 1.0D) < 1.0E-12D) return rewards;
        Map<String, Double> scaled = new LinkedHashMap<>();
        rewards.forEach((id, multiplier) -> scaled.put(id, multiplier * fiveByThreePayoutMultiplier));
        return Map.copyOf(scaled);
    }

    public boolean paidModeReady(boolean packetBridgeReady) {
        if (!paidModeEnabled) return false;
        // Reward mode remains fail-closed on the one requirement that is technically necessary
        // to resolve a paid STOP to the client-visible frame: PacketEvents/stateId support.
        if (requirePacketStateId && !packetBridgeReady) return false;
        return difficulties.stream().filter(SlotDifficulty::enabled).allMatch(difficulty -> difficulty.frameMs() >= 200);
    }
}
