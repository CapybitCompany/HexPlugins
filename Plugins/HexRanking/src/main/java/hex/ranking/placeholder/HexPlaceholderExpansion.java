package hex.ranking.placeholder;

import hex.ranking.HexRanking;
import hex.ranking.model.RankingPlayer;
import hex.ranking.service.RankingService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class HexPlaceholderExpansion extends PlaceholderExpansion {

    private static final String NAME_PREFIX = "top_season_name_";
    private static final String POINTS_PREFIX = "top_season_points_";
    private static final int TOP_LIMIT = 8;
    private static final long REFRESH_PERIOD_TICKS = 20L * 10L;

    private final HexRanking plugin;
    private final RankingService rankingService;

    private volatile Map<Integer, RankingEntry> topSeasonCache = Map.of();
    private BukkitTask refreshTask;

    public HexPlaceholderExpansion(HexRanking plugin, RankingService rankingService) {
        this.plugin = plugin;
        this.rankingService = rankingService;
    }

    public void start() {
        refreshCache();
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::refreshCache,
                REFRESH_PERIOD_TICKS,
                REFRESH_PERIOD_TICKS
        );
    }

    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    @Override
    public String getIdentifier() {
        return "hexrank";
    }

    @Override
    public String getAuthor() {
        return "HexRanking";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null || params.isBlank()) {
            return null;
        }

        Integer namePosition = parsePosition(params, NAME_PREFIX);
        if (namePosition != null) {
            return getNameAt(namePosition);
        }

        Integer pointsPosition = parsePosition(params, POINTS_PREFIX);
        if (pointsPosition != null) {
            return getPointsAt(pointsPosition);
        }

        return null;
    }

    private void refreshCache() {
        rankingService.getTopSeason(TOP_LIMIT).whenComplete((players, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().warning("Could not refresh season placeholders cache: " + throwable.getMessage());
                return;
            }

            Map<Integer, RankingEntry> updatedCache = new HashMap<>();
            int position = 1;
            for (RankingPlayer rankingPlayer : players) {
                if (position > TOP_LIMIT) {
                    break;
                }
                String playerName = rankingPlayer.getPlayerName();
                if (playerName == null || playerName.isBlank()) {
                    playerName = "-";
                }
                updatedCache.put(position, new RankingEntry(playerName, rankingPlayer.getSeasonPoints()));
                position++;
            }
            topSeasonCache = Collections.unmodifiableMap(updatedCache);
        });
    }

    private static Integer parsePosition(String params, String prefix) {
        if (!params.startsWith(prefix)) {
            return null;
        }

        String rawPosition = params.substring(prefix.length());
        if (rawPosition.isBlank()) {
            return null;
        }

        try {
            return Integer.parseInt(rawPosition);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String getNameAt(int position) {
        if (position < 1 || position > TOP_LIMIT) {
            return "-";
        }
        RankingEntry entry = topSeasonCache.get(position);
        if (entry == null) {
            return "-";
        }
        return entry.name();
    }

    private String getPointsAt(int position) {
        if (position < 1 || position > TOP_LIMIT) {
            return "-";
        }
        RankingEntry entry = topSeasonCache.get(position);
        if (entry == null) {
            return "-";
        }
        return Integer.toString(entry.points());
    }

    private record RankingEntry(String name, int points) {
    }
}
