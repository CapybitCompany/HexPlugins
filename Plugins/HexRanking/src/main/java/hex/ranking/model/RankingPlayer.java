package hex.ranking.model;

import java.util.UUID;

public final class RankingPlayer {

    private final UUID uuid;
    private final String playerName;
    private final int globalPoints;
    private final int seasonPoints;

    public RankingPlayer(UUID uuid, String playerName, int globalPoints, int seasonPoints) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.globalPoints = globalPoints;
        this.seasonPoints = seasonPoints;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public int getGlobalPoints() {
        return globalPoints;
    }

    public int getSeasonPoints() {
        return seasonPoints;
    }
}
