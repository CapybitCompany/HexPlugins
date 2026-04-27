package hex.minigames.framework;

import hex.minigames.framework.config.GameTypeConfig;

public record GameTypeRegistration(
        String gameTypeId,
        GameTypeConfig config,
        GameInstanceFactory factory
) {
}

