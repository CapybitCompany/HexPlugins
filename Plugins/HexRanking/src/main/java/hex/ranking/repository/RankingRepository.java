package hex.ranking.repository;

import hex.ranking.model.PointsTable;
import hex.ranking.model.RankingPlayer;

import java.util.List;
import java.util.UUID;

public interface RankingRepository {

    void addPoints(UUID uuid, PointsTable pointsTable, int amount);

    void removePoints(UUID uuid, PointsTable pointsTable, int amount);

    int getGlobalPoints(UUID uuid);

    List<RankingPlayer> getTopGlobal(int limit);
}
