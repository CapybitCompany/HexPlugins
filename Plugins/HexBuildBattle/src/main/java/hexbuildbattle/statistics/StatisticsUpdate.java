package hexbuildbattle.statistics;

import java.util.UUID;

public record StatisticsUpdate(
        UUID playerId,
        String lastKnownName,
        int rankingPoints,
        boolean win,
        boolean podium,
        int buildScore,
        int votesReceived
) {
}
