package hexbuildbattle.placeholder;

import hexbuildbattle.config.ConfigService;
import hexbuildbattle.game.GameManager;
import hexbuildbattle.statistics.PlayerStatistics;
import hexbuildbattle.statistics.StatisticsService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("deprecation")
public final class HexBuildBattlePlaceholderExpansion extends PlaceholderExpansion {

    private static final Pattern TOP_PATTERN = Pattern.compile("top_([1-7])_(name|points)");

    private final JavaPlugin plugin;
    private final GameManager gameManager;
    private final StatisticsService statisticsService;
    private final ConfigService configService;

    public HexBuildBattlePlaceholderExpansion(
            JavaPlugin plugin,
            GameManager gameManager,
            StatisticsService statisticsService,
            ConfigService configService
    ) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.statisticsService = statisticsService;
        this.configService = configService;
    }

    @Override
    public String getIdentifier() {
        return "hexbuildbattle";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
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
    public String onPlaceholderRequest(Player player, String identifier) {
        if (identifier == null) {
            return null;
        }

        String key = identifier.toLowerCase(Locale.ROOT);
        Matcher topMatcher = TOP_PATTERN.matcher(key);
        if (topMatcher.matches()) {
            int index = Integer.parseInt(topMatcher.group(1));
            PlayerStatistics top = statisticsService.top(index);
            if (top == null) {
                return topMatcher.group(2).equals("name")
                        ? configService.config().placeholders().topEmptyName()
                        : configService.config().placeholders().topEmptyPoints();
            }
            return topMatcher.group(2).equals("name")
                    ? top.lastKnownName()
                    : Long.toString(top.rankingPoints());
        }

        return switch (key) {
            case "status" -> gameManager.statusPlaceholder(player);
            case "queue" -> gameManager.queuePlaceholder();
            case "participants" -> gameManager.participantsPlaceholder();
            case "phase" -> gameManager.phasePlaceholder();
            case "theme" -> gameManager.themePlaceholder();
            case "time" -> gameManager.timePlaceholder();
            case "points" -> playerStatistics(player).rankingPoints() + "";
            case "rank" -> {
                PlayerStatistics statistics = playerStatistics(player);
                yield statistics.rank() <= 0 ? "-" : Integer.toString(statistics.rank());
            }
            default -> null;
        };
    }

    private PlayerStatistics playerStatistics(Player player) {
        if (player == null) {
            return PlayerStatistics.empty(new java.util.UUID(0L, 0L), "-");
        }
        return statisticsService.statistics(player.getUniqueId(), player.getName());
    }
}
