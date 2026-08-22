package hexnpc.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record NpcAction(
        String type,
        Map<String, Object> args
) {
    public NpcAction {
        type = Objects.requireNonNull(type, "type").trim().toLowerCase();
        if (type.isEmpty()) {
            throw new IllegalArgumentException("action type is blank");
        }
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    public String asString(String key, String fallback) {
        Object v = args.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    public Map<String, Object> mutableArgs() {
        return new LinkedHashMap<>(args);
    }
}
