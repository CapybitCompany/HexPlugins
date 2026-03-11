package hex.core.placeholder.provider;

import hex.core.placeholder.HexPlaceholderContext;
import hex.core.service.ranking.RankingPositionService;

/**
 * Provides:
 * - rank_global
 * - rank_season
 */
public final class RankPositionPlaceholderProvider implements PlaceholderProvider {

    private final String identifier;
    private final RankingPositionService service;

    public RankPositionPlaceholderProvider(String identifier, RankingPositionService service) {
        this.identifier = identifier;
        this.service = service;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String resolve(HexPlaceholderContext context) {
        var uuid = context.player().getUniqueId();
        int pos = identifier.equalsIgnoreCase("rank_global")
                ? service.getGlobalRank(uuid)
                : service.getSeasonRank(uuid);

        return pos <= 0 ? "-" : Integer.toString(pos);
    }
}

