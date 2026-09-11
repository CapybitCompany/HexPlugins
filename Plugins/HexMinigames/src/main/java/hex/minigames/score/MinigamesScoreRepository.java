package hex.minigames.score;

import java.util.UUID;

public interface MinigamesScoreRepository {
    void initialize();
    boolean available();
    String backendName();
    boolean commitSeriesPoints(UUID sessionId, UUID playerId, int points);
    int globalPoints(UUID playerId);
}
