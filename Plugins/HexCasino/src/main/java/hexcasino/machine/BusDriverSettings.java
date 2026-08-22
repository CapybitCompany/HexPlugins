package hexcasino.machine;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/** Deterministic BusDriver settings intentionally kept independent from legacy CasinoConfig migration. */
public record BusDriverSettings(
        boolean deterministicMode,
        boolean paidModeEnabled,
        String boardSetVersion,
        int requiredBoardCount,
        int lowDecisionMs,
        int mediumDecisionMs,
        int highDecisionMs,
        int snapshotHistoryMs,
        int guaranteedNetworkDelayMs,
        int maxResolvableDelayMs,
        boolean requirePacketStateId,
        String rulesVersion
) {
    public static BusDriverSettings load(FileConfiguration cfg) {
        String p = "bus-driver.deterministic.";
        return new BusDriverSettings(
                cfg.getBoolean(p + "enabled", true),
                cfg.getBoolean(p + "paid-mode-enabled", true),
                cfg.getString(p + "board-set-version", "v1"),
                Math.max(1, cfg.getInt(p + "required-board-count", 100)),
                Math.max(500, cfg.getInt(p + "decision-time-ms.low", 3000)),
                Math.max(500, cfg.getInt(p + "decision-time-ms.medium", 2700)),
                Math.max(500, cfg.getInt(p + "decision-time-ms.high", 2500)),
                Math.max(1000, cfg.getInt(p + "input-resolution.snapshot-history-ms", 5000)),
                Math.max(0, cfg.getInt(p + "input-resolution.guaranteed-network-delay-ms", 1000)),
                Math.max(250, cfg.getInt(p + "input-resolution.max-resolvable-delay-ms", 2000)),
                cfg.getBoolean(p + "input-resolution.require-packet-state-id", true),
                cfg.getString(p + "rules-version", "busdriver-deduction-v3")
        );
    }

    public int decisionTimeMs(int betIndex, int betOptionCount) {
        if (betOptionCount <= 1) return lowDecisionMs;
        double ratio = (double) betIndex / (double) Math.max(1, betOptionCount - 1);
        if (ratio < 0.34D) return lowDecisionMs;
        if (ratio < 0.67D) return mediumDecisionMs;
        return highDecisionMs;
    }

    public String tierId(int betIndex, int betOptionCount) {
        if (betOptionCount <= 1) return "LOW";
        double ratio = (double) betIndex / (double) Math.max(1, betOptionCount - 1);
        if (ratio < 0.34D) return "LOW";
        if (ratio < 0.67D) return "MEDIUM";
        return "HIGH";
    }
}
