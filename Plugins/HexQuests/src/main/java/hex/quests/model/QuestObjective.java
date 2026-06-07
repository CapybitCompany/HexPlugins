package hex.quests.model;

import hex.core.api.messaging.HexMessageData;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

public record QuestObjective(String id, String triggerId, long amount, Map<String, String> filters) {
    public static QuestObjective fromConfig(ConfigurationSection section) {
        Map<String, String> filters = new LinkedHashMap<>();
        ConfigurationSection filterSection = section.getConfigurationSection("filters");
        if (filterSection != null) {
            for (String key : filterSection.getKeys(false)) {
                filters.put(key, String.valueOf(filterSection.get(key)));
            }
        }
        return new QuestObjective(
                section.getString("id", "objective"),
                section.getString("trigger", ""),
                Math.max(1L, section.getLong("amount", 1L)),
                Map.copyOf(filters)
        );
    }

    public static QuestObjective fromMap(Map<?, ?> map) {
        Map<String, String> filters = new LinkedHashMap<>();
        Object rawFilters = map.get("filters");
        if (rawFilters instanceof Map<?, ?> filterMap) {
            for (Map.Entry<?, ?> entry : filterMap.entrySet()) {
                filters.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        Object rawId = map.get("id");
        Object rawTrigger = map.get("trigger");
        Object rawAmount = map.get("amount");
        return new QuestObjective(
                String.valueOf(rawId != null ? rawId : "objective"),
                String.valueOf(rawTrigger != null ? rawTrigger : ""),
                Math.max(1L, longValue(rawAmount)),
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

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 1L;
    }
}


