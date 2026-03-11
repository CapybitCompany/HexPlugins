package hex.core.database.model;

import java.util.UUID;

/**
 * Single leaderboard entry.
 *
 * playerName can be null if the name isn't available in DB.
 */
public record RankingTopEntry(
        UUID uuid,
        String playerName,
        int points
) {
}

