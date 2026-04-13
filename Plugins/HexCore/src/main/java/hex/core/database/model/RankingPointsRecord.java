package hex.core.database.model;

import java.time.Instant;
import java.util.UUID;

public record RankingPointsRecord(
        UUID uuid,
        int globalPoints,
        int seasonPoints,
        Instant updatedAt
) {
}

