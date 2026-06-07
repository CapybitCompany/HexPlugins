package hex.collections.model;

import hex.core.api.messaging.HexMessageData;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

public record CollectionSource(String triggerId, long fixedAmount, String amountFrom, Map<String, String> filters) {
    public static CollectionSource fromConfig(ConfigurationSection section) {
        Map<String, String> filters = new LinkedHashMap<>();
        ConfigurationSection filterSection = section.getConfigurationSection("filters");
        if (filterSection != null) {
            for (String key : filterSection.getKeys(false)) {
                filters.put(key, String.valueOf(filterSection.get(key)));
            }
        }
        return new CollectionSource(
                section.getString("trigger", ""),
                section.getLong("amount", 1L),
                section.getString("amount-from", ""),
                Map.copyOf(filters)
        );
    }

    public static CollectionSource fromMap(Map<?, ?> map) {
        Map<String, String> filters = new LinkedHashMap<>();
        Object rawFilters = map.get("filters");
        if (rawFilters instanceof Map<?, ?> filterMap) {
            for (Map.Entry<?, ?> entry : filterMap.entrySet()) {
                filters.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        Object trigger = map.containsKey("trigger") ? map.get("trigger") : "";
        Object amountFrom = map.containsKey("amount-from") ? map.get("amount-from") : "";
        return new CollectionSource(
                String.valueOf(trigger),
                longValue(map.get("amount"), 1L),
                String.valueOf(amountFrom),
                Map.copyOf(filters)
        );
    }

    public boolean matches(HexMessageData data) {
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            if (!filter.getValue().equalsIgnoreCase(TriggerData.string(data, filter.getKey(), ""))) {
                return false;
            }
        }
        return true;
    }

    public long amount(HexMessageData data) {
        if (amountFrom != null && !amountFrom.isBlank()) {
            return Math.max(0L, TriggerData.longValue(data, amountFrom, fixedAmount));
        }
        return Math.max(0L, fixedAmount);
    }

    private static long longValue(Object value, long def) {
        return value instanceof Number number ? number.longValue() : def;
    }
}


