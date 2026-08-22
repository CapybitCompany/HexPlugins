package hex.quests.model;

import hex.core.api.messaging.HexMessageData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record QuestObjective(
        String id,
        String triggerId,
        long target,
        String progressKey,
        Map<String, Object> filters
) {
    public static QuestObjective fromMap(Map<?, ?> map) {
        String id = string(map.get("id"), "objective");
        String trigger = string(map.get("trigger"), "").toLowerCase(Locale.ROOT);
        long target = Math.max(1L, longValue(map.get("amount"), 1L));
        String progressKey = string(map.get("progress-key"), "amount").toLowerCase(Locale.ROOT);
        Map<String, Object> filters = new LinkedHashMap<>();
        Object rawFilters = map.get("filters");
        if (rawFilters instanceof Map<?, ?> source) {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                Object value = entry.getValue();
                if (value instanceof List<?> list) {
                    List<String> normalized = new ArrayList<>();
                    for (Object item : list) normalized.add(String.valueOf(item));
                    filters.put(key, List.copyOf(normalized));
                } else if (value != null) {
                    filters.put(key, String.valueOf(value));
                }
            }
        }
        return new QuestObjective(id, trigger, target, progressKey, Map.copyOf(filters));
    }

    public boolean matches(HexMessageData data) {
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            String key = filter.getKey();
            Object expected = filter.getValue();
            List<String> actualList = data.getStringList(key);
            if (expected instanceof List<?> expectedList) {
                boolean matched = false;
                if (!actualList.isEmpty()) {
                    for (Object expectedValue : expectedList) {
                        if (containsIgnoreCase(actualList, String.valueOf(expectedValue))) {
                            matched = true;
                            break;
                        }
                    }
                } else {
                    String actual = TriggerData.string(data, key, "");
                    for (Object expectedValue : expectedList) {
                        if (String.valueOf(expectedValue).equalsIgnoreCase(actual)) {
                            matched = true;
                            break;
                        }
                    }
                }
                if (!matched) return false;
            } else {
                String expectedValue = String.valueOf(expected);
                if (!actualList.isEmpty()) {
                    if (!containsIgnoreCase(actualList, expectedValue)) return false;
                } else if (!expectedValue.equalsIgnoreCase(TriggerData.string(data, key, ""))) {
                    return false;
                }
            }
        }
        return true;
    }

    public long delta(HexMessageData data) {
        return Math.max(0L, TriggerData.longValue(data, progressKey, progressKey.equals("amount") ? 1L : 0L));
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        for (String value : values) if (expected.equalsIgnoreCase(value)) return true;
        return false;
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }
}
