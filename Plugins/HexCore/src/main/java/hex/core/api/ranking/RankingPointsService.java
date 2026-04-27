package hex.core.api.ranking;

import java.util.UUID;

/**
 * @deprecated Legacy API, not used by current HexCore wiring.
 * Use {@link hex.core.api.HexApi#rankingPoints()} instead.
 */
@Deprecated(since = "1.0")
public interface RankingPointsService {

    int getGlobalPoints(UUID uuid);

    int getSeasonPoints(UUID uuid);
}
