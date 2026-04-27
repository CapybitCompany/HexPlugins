package hex.statsapi.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class StatRegistry {

    private final StatsProperties properties;
    private Map<String, StatDefinition> definitionsById = Map.of();

    public StatRegistry(StatsProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initialize() {
        Map<String, StatDefinition> map = new LinkedHashMap<>();
        for (StatDefinition definition : properties.getDefinitions()) {
            String id = definition.getId().toLowerCase(Locale.ROOT);
            if (map.containsKey(id)) {
                throw new IllegalStateException("Duplicate stat id: " + id);
            }
            map.put(id, definition);
        }
        this.definitionsById = Collections.unmodifiableMap(map);
    }

    public Map<String, StatDefinition> all() {
        return definitionsById;
    }

    public StatDefinition get(String statId) {
        if (statId == null) {
            return null;
        }
        return definitionsById.get(statId.toLowerCase(Locale.ROOT));
    }
}

