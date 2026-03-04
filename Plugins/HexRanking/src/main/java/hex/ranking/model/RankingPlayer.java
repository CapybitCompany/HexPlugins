package hex.ranking.model;

import java.util.UUID;

public final class RankingPlayer {

    private final UUID uuid;
    private final int globalPoints;
    private final int seasonPoints;

    public RankingPlayer(UUID uuid, int globalPoints, int seasonPoints) {
        this.uuid = uuid;
        this.globalPoints = globalPoints;
        this.seasonPoints = seasonPoints;
    }

    public UUID getUuid() {
        return uuid;
    }

    public int getGlobalPoints() {
        return globalPoints;
    }

    public int getSeasonPoints() {
        return seasonPoints;
    }
}
