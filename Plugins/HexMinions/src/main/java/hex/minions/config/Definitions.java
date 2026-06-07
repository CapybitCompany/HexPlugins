package hex.minions.config;

import java.util.Map;

public record Definitions(Map<String, MinionTypeDefinition> minionTypes, Map<String, ResourceDefinition> resources, Map<String, AppearanceDefinition> appearances) {
    public MinionTypeDefinition requireType(String id) {
        MinionTypeDefinition type = minionTypes.get(id);
        if (type == null) throw new IllegalArgumentException("Unknown minion type: " + id);
        return type;
    }

    public AppearanceDefinition appearance(String id) {
        AppearanceDefinition definition = appearances.get(id);
        return definition == null ? AppearanceDefinition.fallback(id) : definition;
    }
}

