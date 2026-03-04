package hex.core.api;
import hex.core.api.config.ConfigService;
import hex.core.api.db.DatabaseService;
import hex.core.api.flags.FeatureFlagService;
import hex.core.api.region.RegionService;
import hex.core.api.ui.UiService;

public interface HexApi {
    ConfigService configs();
    FeatureFlagService flags();
    UiService ui();
    RegionService regions();
    DatabaseService db();
}
