package hex.core.placeholder.provider;

import hex.core.database.model.RankingTopEntry;
import hex.core.placeholder.HexPlaceholderContext;
import hex.core.service.ranking.RankingTopService;

import java.util.Locale;

/**
 * Prefix provider for placeholders:
 * - top_season_<pos>_name
 * - top_season_<pos>_points
 * - top_global_<pos>_name
 * - top_global_<pos>_points
 */
public final class TopRankingPlaceholderProvider implements PlaceholderProvider {

    private final RankingTopService topService;

    public TopRankingPlaceholderProvider(RankingTopService topService) {
        this.topService = topService;
    }

    @Override
    public String getIdentifier() {
        // Not used for lookup (registered as prefix provider).
        return "top";
    }

    @Override
    public String resolve(HexPlaceholderContext context) {
        String identifier = context.identifier();
        if (identifier == null) return null;

        String id = identifier.toLowerCase(Locale.ROOT);
        boolean season;
        if (id.startsWith("top_season_")) {
            season = true;
            id = id.substring("top_season_".length());
        } else if (id.startsWith("top_global_")) {
            season = false;
            id = id.substring("top_global_".length());
        } else {
            return null;
        }

        String[] parts = id.split("_", 2);
        if (parts.length != 2) return null;

        int pos;
        try {
            pos = Integer.parseInt(parts[0]);
        } catch (NumberFormatException ex) {
            return null;
        }

        String field = parts[1];
        RankingTopEntry entry = season ? topService.getTopSeason(pos) : topService.getTopGlobal(pos);

        return switch (field) {
            case "name" -> (entry.playerName() == null || entry.playerName().isBlank()) ? "-" : entry.playerName();
            case "points" -> Integer.toString(entry.points());
            default -> null;
        };
    }
}
