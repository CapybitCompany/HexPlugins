package hexbuildbattle.statistics;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface StatisticsRepository extends AutoCloseable {

    CompletableFuture<Void> initialize();

    CompletableFuture<List<PlayerStatistics>> loadAll();

    CompletableFuture<Void> applyRoundUpdates(List<StatisticsUpdate> updates);

    @Override
    void close();
}
