package hexbuildbattle.score;

import java.util.UUID;

public record RoundPlacement(
        int place,
        UUID ownerId,
        String ownerName,
        int totalPoints,
        int validVoteCount,
        int rankingPoints
) {
}
