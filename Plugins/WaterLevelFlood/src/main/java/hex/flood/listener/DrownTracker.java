package hex.flood.listener;

import hex.flood.WaterLevelFloodPlugin;
import hex.flood.config.FloodConfig;

/**
 * @deprecated Mechanika tonięcia została przeniesiona do pluginu WaterDrawn.
 * Ta klasa pozostaje jako stub tylko dla kompatybilności źródeł.
 */
@Deprecated
public class DrownTracker {

    public DrownTracker(WaterLevelFloodPlugin plugin, FloodConfig config, Object ignoredManager) {
        // no-op
    }

    public void updateConfig(FloodConfig config) {
        // no-op
    }

    public void shutdown() {
        // no-op
    }
}
