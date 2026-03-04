package hex.ranking.repository;

import hex.ranking.model.RankingPlayer;

import java.util.List;
import java.util.UUID;

public interface RankingRepository {

    void addGlobalPoints(UUID uuid, int amount);

    void removeGlobalPoints(UUID uuid, int amount);

    int getGlobalPoints(UUID uuid);

    List<RankingPlayer> getTopGlobal(int limit);
}
