package hex.minigames.framework.config;

import java.util.Map;
import java.util.Set;

public final class MinigamesConfig {

    private final Map<String, GameTypeConfig> gameTypes;

    public MinigamesConfig(Map<String, GameTypeConfig> gameTypes) {
        this.gameTypes = Map.copyOf(gameTypes);
    }

    public Set<String> gameTypes() {
        return gameTypes.keySet();
    }

    public GameTypeConfig requireGameType(String id) {
        GameTypeConfig cfg = gameTypes.get(id);
        if (cfg == null) {
            throw new IllegalArgumentException("Game type missing in config: " + id);
        }
        return cfg;
    }

    public Map<String, GameTypeConfig> asMap() {
        return gameTypes;
    }
}

