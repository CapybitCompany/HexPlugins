package hex.skills.model;

import hex.core.api.messaging.HexMessageData;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

public record XpSource(String triggerId, int xp, double amountMultiplier, Map<String, String> filters) {
    public static XpSource fromConfig(ConfigurationSection section) {
        Map<String, String> filters = new LinkedHashMap<>();
        ConfigurationSection filterSection = section.getConfigurationSection("filters");
        if (filterSection != null) {
            for (String key : filterSection.getKeys(false)) {
                filters.put(key, String.valueOf(filterSection.get(key)));
            }
        }
        return new XpSource(
                section.getString("trigger", ""),
                section.getInt("xp", 0),
                section.getDouble("amount-multiplier", 0.0D),
                Map.copyOf(filters)
        );
    }

    public static XpSource fromMap(Map<?, ?> map) {
        Map<String, String> filters = new LinkedHashMap<>();
        Object rawFilters = map.get("filters");
        if (rawFilters instanceof Map<?, ?> filterMap) {
            for (Map.Entry<?, ?> entry : filterMap.entrySet()) {
                filters.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        Object trigger = map.containsKey("trigger") ? map.get("trigger") : "";
        return new XpSource(
                String.valueOf(trigger),
                intValue(map.get("xp"), 0),
                doubleValue(map.get("amount-multiplier"), 0.0D),
                Map.copyOf(filters)
        );
    }

    public boolean matches(HexMessageData data) {
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            String actual = TriggerData.string(data, filter.getKey(), "");
            if (!filter.getValue().equalsIgnoreCase(actual)) {
                return false;
            }
        }
        return true;
    }

    public long xpAmount(HexMessageData data) {
        long amount = Math.max(1L, TriggerData.longValue(data, "amount", 1L));
        return Math.max(0L, xp + Math.round(amount * amountMultiplier));
    }

    private static int intValue(Object value, int def) {
        return value instanceof Number number ? number.intValue() : def;
    }

    private static double doubleValue(Object value, double def) {
        return value instanceof Number number ? number.doubleValue() : def;
    }
}


