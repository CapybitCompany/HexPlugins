package hex.core.api.messaging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HexMessageData {

    public static final HexMessageData EMPTY = new HexMessageData(Map.of());

    private final Map<String, Object> values;

    private HexMessageData(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    public String getString(String key, String def) {
        Object value = values.get(key);
        return value instanceof String s ? s : (value != null ? String.valueOf(value) : def);
    }

    public int getInt(String key, int def) {
        Object value = values.get(key);
        return value instanceof Number n ? n.intValue() : def;
    }

    public long getLong(String key, long def) {
        Object value = values.get(key);
        return value instanceof Number n ? n.longValue() : def;
    }

    public double getDouble(String key, double def) {
        Object value = values.get(key);
        return value instanceof Number n ? n.doubleValue() : def;
    }

    public boolean getBool(String key, boolean def) {
        Object value = values.get(key);
        return value instanceof Boolean b ? b : def;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public HexMessageData getSection(String key) {
        Object value = values.get(key);
        return value instanceof HexMessageData data ? data : EMPTY;
    }

    public List<String> getStringList(String key) {
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(item != null ? String.valueOf(item) : "");
            }
            return Collections.unmodifiableList(result);
        }
        return List.of();
    }

    public List<Integer> getIntList(String key) {
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            List<Integer> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(item instanceof Number n ? n.intValue() : 0);
            }
            return Collections.unmodifiableList(result);
        }
        return List.of();
    }

    public List<HexMessageData> getSectionList(String key) {
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            List<HexMessageData> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(item instanceof HexMessageData data ? data : EMPTY);
            }
            return Collections.unmodifiableList(result);
        }
        return List.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Object> data = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder put(String key, String value) {
            data.put(key, value);
            return this;
        }

        public Builder put(String key, int value) {
            data.put(key, value);
            return this;
        }

        public Builder put(String key, long value) {
            data.put(key, value);
            return this;
        }

        public Builder put(String key, double value) {
            data.put(key, value);
            return this;
        }

        public Builder put(String key, boolean value) {
            data.put(key, value);
            return this;
        }

        public Builder putSection(String key, HexMessageData section) {
            data.put(key, section);
            return this;
        }

        public Builder putStringList(String key, List<String> list) {
            data.put(key, List.copyOf(list));
            return this;
        }

        public Builder putIntList(String key, List<Integer> list) {
            data.put(key, List.copyOf(list));
            return this;
        }

        public Builder putSectionList(String key, List<HexMessageData> list) {
            data.put(key, List.copyOf(list));
            return this;
        }

        public HexMessageData build() {
            return new HexMessageData(new LinkedHashMap<>(data));
        }
    }
}

