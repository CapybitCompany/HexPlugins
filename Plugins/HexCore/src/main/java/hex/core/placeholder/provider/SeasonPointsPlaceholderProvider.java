package hex.core.placeholder.provider;

import hex.core.placeholder.HexPlaceholderContext;

public final class SeasonPointsPlaceholderProvider implements PlaceholderProvider {

    @Override
    public String getIdentifier() {
        return "season_points";
    }

    @Override
    public String resolve(HexPlaceholderContext context) {
        if (context.player() == null) {
            return "-";
        }
        int points = context.api().rankingPoints().getSeasonPoints(context.player().getUniqueId());
        return Integer.toString(points);
    }
}

