package hex.core.model;

import java.util.UUID;

public record RankingPointsRecord(
        UUID playerUuid,
        int globalPoints,
        int seasonPoints
) {
}

