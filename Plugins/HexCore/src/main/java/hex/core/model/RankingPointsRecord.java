package hex.core.model;

import java.util.UUID;

/**
 * @deprecated Legacy model kept for compatibility.
 * Use {@link hex.core.database.model.RankingPointsRecord} instead.
 */
@Deprecated(forRemoval = false, since = "1.0")
public record RankingPointsRecord(
        UUID playerUuid,
        int globalPoints,
        int seasonPoints
) {
}
