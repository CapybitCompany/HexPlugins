package hexbuildbattle.statistics;

import hexbuildbattle.score.RoundPlacement;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class StatisticsService {

    private final JavaPlugin plugin;
    private final StatisticsRepository repository;
    private final Logger logger;
    private final Map<UUID, PlayerStatistics> cache = new ConcurrentHashMap<>();
    private volatile List<PlayerStatistics> top = List.of();

    public StatisticsService(JavaPlugin plugin, StatisticsRepository repository, Logger logger) {
        this.plugin = plugin;
        this.repository = repository;
        this.logger = logger;
    }

    public void initialize() {
        repository.initialize().thenRun(this::refreshCache);
    }

    public void applyRoundPlacements(List<RoundPlacement> placements) {
        List<StatisticsUpdate> updates = new ArrayList<>();
        for (RoundPlacement placement : placements) {
            Player player = Bukkit.getPlayer(placement.ownerId());
            String name = player == null ? placement.ownerName() : player.getName();
            updates.add(new StatisticsUpdate(
                    placement.ownerId(),
                    name,
                    placement.rankingPoints(),
                    placement.place() == 1,
                    placement.place() <= 3,
                    placement.totalPoints(),
                    placement.validVoteCount()
            ));
        }
        repository.applyRoundUpdates(updates).thenRun(this::refreshCache);
    }

    public PlayerStatistics statistics(UUID playerId, String fallbackName) {
        return cache.getOrDefault(playerId, PlayerStatistics.empty(playerId, fallbackName));
    }

    public PlayerStatistics top(int oneBasedIndex) {
        if (oneBasedIndex <= 0 || oneBasedIndex > top.size()) {
            return null;
        }
        return top.get(oneBasedIndex - 1);
    }

    public void close() {
        repository.close();
    }

    private void refreshCache() {
        repository.loadAll().thenAccept(loaded -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            cache.clear();
            for (PlayerStatistics statistic : loaded) {
                cache.put(statistic.playerId(), statistic);
            }
            top = loaded.stream()
                    .sorted(Comparator
                            .comparingLong(PlayerStatistics::rankingPoints).reversed()
                            .thenComparing(PlayerStatistics::wins, Comparator.reverseOrder())
                            .thenComparing(PlayerStatistics::podiums, Comparator.reverseOrder())
                            .thenComparing(PlayerStatistics::lastKnownName))
                    .limit(7)
                    .toList();
        })).exceptionally(failure -> {
            logger.warning("Could not refresh HexBuildBattle statistics cache: " + failure.getMessage());
            return null;
        });
    }
}
