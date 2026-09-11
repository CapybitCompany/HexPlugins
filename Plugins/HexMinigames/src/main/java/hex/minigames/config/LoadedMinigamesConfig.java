package hex.minigames.config;

import hex.minigames.game.MinigameDefinition;

import java.util.List;
import java.util.Map;

public record LoadedMinigamesConfig(
        GlobalConfig global,
        Messages messages,
        Map<String, MinigameDefinition> gameDefinitions,
        List<String> errors
) {
    public LoadedMinigamesConfig {
        gameDefinitions = gameDefinitions == null ? Map.of() : Map.copyOf(gameDefinitions);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean valid() {
        return global.valid() && errors.isEmpty();
    }
}
