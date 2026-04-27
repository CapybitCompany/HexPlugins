package hex.core.api.ranking;

import java.util.UUID;

public interface RankingPointsService {

    int getGlobalPoints(UUID uuid);

    int getSeasonPoints(UUID uuid);
}

