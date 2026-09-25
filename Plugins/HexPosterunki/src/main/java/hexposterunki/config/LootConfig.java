package hexposterunki.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loot container configuration.
 *
 * @param fillPhase when the configured containers are filled during a run
 * @param resetMode what happens to the containers when the run is reset
 * @param tables    named loot tables
 * @param bindings  outpost id -> (container name -> loot table id)
 */
public record LootConfig(
        boolean enabled,
        FillPhase fillPhase,
        ResetMode resetMode,
        String defaultTable,
        String initialTable,
        Map<String, LootTable> tables,
        Map<String, Map<String, String>> bindings
) {

    public LootConfig {
        fillPhase = fillPhase == null ? FillPhase.WAVES_CLEARED : fillPhase;
        resetMode = resetMode == null ? ResetMode.CLEAR : resetMode;
        defaultTable = defaultTable == null || defaultTable.isBlank() ? "default" : defaultTable.trim();
        initialTable = initialTable == null || initialTable.isBlank() ? null : initialTable.trim();
        tables = tables == null ? Map.of() : Map.copyOf(tables);
        bindings = bindings == null ? Map.of() : Map.copyOf(bindings);
    }

    public static LootConfig disabled() {
        return new LootConfig(false, FillPhase.WAVES_CLEARED, ResetMode.CLEAR, "default", null, Map.of(), Map.of());
    }

    public String tableFor(String outpostId, String containerName) {
        Map<String, String> perOutpost = bindings.get(outpostId);
        if (perOutpost == null) {
            return defaultTable;
        }
        return perOutpost.getOrDefault(containerName, defaultTable);
    }

    public enum FillPhase {
        /** Filled as soon as a town claims the outpost. */
        RUN_START,
        /** Filled when the last wave is cleared (default). */
        WAVES_CLEARED,
        /** Filled only after the whole encounter including the boss is finished. */
        COMPLETED
    }

    public enum ResetMode {
        /** Containers end up empty. */
        CLEAR,
        /** Containers are refilled from the configured {@code initial-table}. */
        RESTORE_INITIAL
    }

    /** @param rolls how many weighted entries are drawn per container */
    public record LootTable(int rolls, List<LootEntry> entries) {

        public LootTable {
            rolls = Math.max(0, rolls);
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    public record LootEntry(String material, int minAmount, int maxAmount, int weight) {

        public LootEntry {
            Objects.requireNonNull(material, "material");
            minAmount = Math.max(1, minAmount);
            maxAmount = Math.max(minAmount, maxAmount);
            weight = Math.max(0, weight);
        }
    }
}
