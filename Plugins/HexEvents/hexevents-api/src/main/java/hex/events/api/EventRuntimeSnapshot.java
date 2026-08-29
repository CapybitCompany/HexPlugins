package hex.events.api;

import java.util.Map;

public record EventRuntimeSnapshot(boolean recoverable, Map<String, String> data) {
    public EventRuntimeSnapshot { data = data == null ? Map.of() : Map.copyOf(data); }
    public static EventRuntimeSnapshot unavailable() { return new EventRuntimeSnapshot(false, Map.of()); }
}
