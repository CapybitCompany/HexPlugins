package hex.minigames.score;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class SeriesScore {
    private final Map<UUID, Integer> points = new LinkedHashMap<>();

    public int add(UUID playerId, int amount) {
        int updated = points.getOrDefault(playerId, 0) + amount;
        points.put(playerId, updated);
        return updated;
    }

    public int points(UUID playerId) {
        return points.getOrDefault(playerId, 0);
    }

    public Map<UUID, Integer> snapshot() {
        return Map.copyOf(points);
    }
}
