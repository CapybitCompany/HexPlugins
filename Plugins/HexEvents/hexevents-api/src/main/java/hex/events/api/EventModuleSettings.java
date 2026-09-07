package hex.events.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class EventModuleSettings {
    private final Map<String, Object> values;

    public EventModuleSettings(Map<String, ?> values) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (values != null) values.forEach((k, v) -> copy.put(k, deepCopy(v)));
        this.values = Collections.unmodifiableMap(copy);
    }

    public static EventModuleSettings empty() { return new EventModuleSettings(Map.of()); }

    public Map<String, Object> asMap() { return values; }
    public Optional<Object> get(String key) { return Optional.ofNullable(values.get(key)); }
    public String string(String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
    public int integer(String key, int fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value != null) try { return Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException ignored) { }
        return fallback;
    }
    public boolean bool(String key, boolean fallback) {
        Object value = values.get(key);
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(String.valueOf(value));
        return fallback;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), deepCopy(v)));
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof List<?> list) return List.copyOf(list.stream().map(EventModuleSettings::deepCopy).toList());
        return value;
    }
}
