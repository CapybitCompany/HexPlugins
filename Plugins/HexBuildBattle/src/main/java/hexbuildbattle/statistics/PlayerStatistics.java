package hexbuildbattle.statistics;

import java.util.UUID;

public record PlayerStatistics(
        UUID playerId,
        String lastKnownName,
        long rankingPoints,
        int gamesPlayed,
        int wins,
        int podiums,
        long totalBuildScore,
        long totalVotesReceived,
        int rank
) {
    public static PlayerStatistics empty(UUID playerId, String name) {
        return new PlayerStatistics(playerId, name, 0L, 0, 0, 0, 0L, 0L, 0);
    }

    public PlayerStatistics withRank(int newRank) {
        return new PlayerStatistics(
                playerId,
                lastKnownName,
                rankingPoints,
                gamesPlayed,
                wins,
                podiums,
                totalBuildScore,
                totalVotesReceived,
                newRank
        );
    }
}
