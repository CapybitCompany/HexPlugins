package hex.events.config;

import hex.events.model.EventDefinition;
import java.util.LinkedHashMap;
import java.util.Map;

public record EventsConfig(Map<String, EventDefinition> definitions) {
    public EventsConfig { definitions = Map.copyOf(new LinkedHashMap<>(definitions)); }
}
