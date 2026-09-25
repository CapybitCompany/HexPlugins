package hexposterunki.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Reads {@code loot.yml} into the immutable {@link LootConfig} model. */
public final class LootLoader {

    public LootConfig load(FileConfiguration configuration, Logger logger) {
        boolean enabled = configuration.getBoolean("enabled", true);
        LootConfig.FillPhase fillPhase = parseEnum(LootConfig.FillPhase.class,
                configuration.getString("fill-phase"), LootConfig.FillPhase.WAVES_CLEARED, logger, "fill-phase");
        LootConfig.ResetMode resetMode = parseEnum(LootConfig.ResetMode.class,
                configuration.getString("reset-mode"), LootConfig.ResetMode.CLEAR, logger, "reset-mode");
        String defaultTable = configuration.getString("default-table", "default");

        Map<String, LootConfig.LootTable> tables = new LinkedHashMap<>();
        ConfigurationSection tableSection = configuration.getConfigurationSection("tables");
        if (tableSection != null) {
            for (String tableId : tableSection.getKeys(false)) {
                ConfigurationSection table = tableSection.getConfigurationSection(tableId);
                if (table == null) {
                    continue;
                }
                int rolls = table.getInt("rolls", 1);
                List<LootConfig.LootEntry> entries = new ArrayList<>();
                List<?> rawEntries = table.getList("entries");
                if (rawEntries != null) {
                    for (Object rawEntry : rawEntries) {
                        if (!(rawEntry instanceof Map<?, ?> entryMap)) {
                            continue;
                        }
                        Object material = entryMap.get("material");
                        if (material == null || material.toString().isBlank()) {
                            logger.warning("[loot] tabela '" + tableId + "' ma wpis bez 'material' - pomijam.");
                            continue;
                        }
                        int min = entryMap.get("min") instanceof Number number ? number.intValue() : 1;
                        int max = entryMap.get("max") instanceof Number number ? number.intValue() : min;
                        int weight = entryMap.get("weight") instanceof Number number ? number.intValue() : 1;
                        entries.add(new LootConfig.LootEntry(
                                material.toString().trim().toUpperCase(Locale.ROOT), min, max, weight));
                    }
                }
                tables.put(tableId.trim().toLowerCase(Locale.ROOT), new LootConfig.LootTable(rolls, entries));
            }
        }

        Map<String, Map<String, String>> bindings = new LinkedHashMap<>();
        ConfigurationSection bindingSection = configuration.getConfigurationSection("containers");
        if (bindingSection != null) {
            for (String outpostId : bindingSection.getKeys(false)) {
                ConfigurationSection perOutpost = bindingSection.getConfigurationSection(outpostId);
                if (perOutpost == null) {
                    continue;
                }
                Map<String, String> containerTables = new LinkedHashMap<>();
                for (String containerName : perOutpost.getKeys(false)) {
                    containerTables.put(containerName.trim().toLowerCase(Locale.ROOT),
                            String.valueOf(perOutpost.getString(containerName, defaultTable))
                                    .trim().toLowerCase(Locale.ROOT));
                }
                bindings.put(outpostId.trim().toLowerCase(Locale.ROOT), Map.copyOf(containerTables));
            }
        }

        String initialTable = configuration.getString("initial-table");
        return new LootConfig(enabled, fillPhase, resetMode,
                defaultTable == null ? "default" : defaultTable.trim().toLowerCase(Locale.ROOT),
                initialTable == null || initialTable.isBlank() ? null : initialTable.trim().toLowerCase(Locale.ROOT),
                tables, bindings);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback, Logger logger, String field) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            logger.warning("[loot] Nieznana wartość '" + raw + "' dla '" + field + "', używam " + fallback + ".");
            return fallback;
        }
    }
}
