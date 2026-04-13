package hex.minigames.framework;

import hex.minigames.framework.config.GameTypeConfig;

public interface GameInstanceFactory {
    GameInstance create(String instanceId, String gameTypeId, String worldName, GameTypeConfig config);
}

