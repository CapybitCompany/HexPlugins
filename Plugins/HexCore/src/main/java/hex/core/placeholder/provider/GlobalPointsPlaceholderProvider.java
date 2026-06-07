package hex.core.placeholder.provider;

import hex.core.placeholder.HexPlaceholderContext;

public final class GlobalPointsPlaceholderProvider implements PlaceholderProvider {

    @Override
    public String getIdentifier() {
        return "global_points";
    }

    @Override
    public String resolve(HexPlaceholderContext context) {
        if (context.player() == null) {
            return "-";
        }
        int points = context.api().rankingPoints().getGlobalPoints(context.player().getUniqueId());
        return Integer.toString(points);
    }
}

