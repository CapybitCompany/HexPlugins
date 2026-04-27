package hex.core.api;

import hex.core.api.config.ConfigService;
import hex.core.api.db.DatabaseService;
import hex.core.api.flags.FeatureFlagService;
import hex.core.api.region.RegionService;
import hex.core.api.ui.UiService;
import hex.core.service.coins.CoinsService;
import hex.core.service.ranking.RankingPointsService;

public interface HexApi {
    ConfigService configs();
    FeatureFlagService flags();
    UiService ui();
    RegionService regions();
    DatabaseService db();
    RankingPointsService rankingPoints();
    CoinsService coins();
}
