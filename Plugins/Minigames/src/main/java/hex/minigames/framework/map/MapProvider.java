package hex.minigames.framework.map;

import hex.minigames.framework.GameInstance;
import hex.minigames.framework.config.GameTypeConfig;

public interface MapProvider {
    String pickMap(String gameTypeId, GameTypeConfig cfg);

    void reset(GameInstance instance);
}

