package hex.minigames.game;

import java.util.Map;
import java.util.UUID;

public record RoundResult(Map<UUID, PlayerRoundResult> players, Map<String, String> metadata) {
    public RoundResult {
        players = players == null ? Map.of() : Map.copyOf(players);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static RoundResult empty() {
        return new RoundResult(Map.of(), Map.of());
    }
}
