package hex.core.service.ranking;

import hex.core.api.ranking.RankingPointsService;
import hex.core.model.RankingPointsRecord;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RankingPointsServiceImpl implements RankingPointsService {

    private final Map<UUID, RankingPointsRecord> cache = new ConcurrentHashMap<>();

    @Override
    public int getGlobalPoints(UUID uuid) {
        // TODO add read-through cache backed by repository + periodic refresh.
        return getRecord(uuid).globalPoints();
    }

    @Override
    public int getSeasonPoints(UUID uuid) {
        // TODO add read-through cache backed by repository + periodic refresh.
        return getRecord(uuid).seasonPoints();
    }

    private RankingPointsRecord getRecord(UUID uuid) {
        return cache.computeIfAbsent(uuid, id -> {
            // TODO load real values from Ranking repository/service (DB/API).
            return new RankingPointsRecord(id, 0, 0);
        });
    }
}

