package hex.minigames.config;

import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;

public record PregameConfig(
        boolean enabled,
        CuboidRegion region,
        LocationSpec spawn,
        int minimumPlayers,
        int drawDurationSeconds,
        int countdownSeconds
) {
}
