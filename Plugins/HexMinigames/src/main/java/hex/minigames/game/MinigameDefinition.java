package hex.minigames.game;

import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record MinigameDefinition(
        String id,
        String displayName,
        boolean enabled,
        boolean implemented,
        boolean internal,
        int minPlayers,
        int maxPlayers,
        int weight,
        Optional<CuboidRegion> region,
        List<LocationSpec> participantSpawns,
        Optional<LocationSpec> spectatorSpawn,
        int roundTimeSeconds,
        Map<String, Object> settings,
        String sourcePath
) {
    public MinigameDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id");
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        minPlayers = Math.max(0, minPlayers);
        maxPlayers = Math.max(0, maxPlayers);
        weight = Math.max(1, weight);
        region = region == null ? Optional.empty() : region;
        participantSpawns = participantSpawns == null ? List.of() : List.copyOf(participantSpawns);
        spectatorSpawn = spectatorSpawn == null ? Optional.empty() : spectatorSpawn;
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        sourcePath = sourcePath == null ? "" : sourcePath;
    }

    public boolean playerCountAllowed(int players) {
        if (players < minPlayers) return false;
        return maxPlayers <= 0 || players <= maxPlayers;
    }

    public MinigameDefinition withImplementation(boolean implemented, boolean internal) {
        return new MinigameDefinition(
                id,
                displayName,
                enabled,
                implemented,
                internal,
                minPlayers,
                maxPlayers,
                weight,
                region,
                participantSpawns,
                spectatorSpawn,
                roundTimeSeconds,
                settings,
                sourcePath
        );
    }

    public MinigameDefinition withEnabled(boolean enabled) {
        return new MinigameDefinition(
                id,
                displayName,
                enabled,
                implemented,
                internal,
                minPlayers,
                maxPlayers,
                weight,
                region,
                participantSpawns,
                spectatorSpawn,
                roundTimeSeconds,
                settings,
                sourcePath
        );
    }
}
