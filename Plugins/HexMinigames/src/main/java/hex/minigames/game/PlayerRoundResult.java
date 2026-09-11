package hex.minigames.game;

import java.util.Map;
import java.util.OptionalInt;

public record PlayerRoundResult(
        int points,
        OptionalInt placement,
        boolean completed,
        boolean failed,
        Map<String, String> data
) {
    public PlayerRoundResult {
        placement = placement == null ? OptionalInt.empty() : placement;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static PlayerRoundResult points(int points) {
        return new PlayerRoundResult(points, OptionalInt.empty(), false, false, Map.of());
    }
}
