package hex.minigames.framework.map;

import hex.minigames.framework.GameInstance;
import hex.minigames.framework.config.GameTypeConfig;

public final class TemplateCloneMapProvider implements MapProvider {

    @Override
    public String pickMap(String gameTypeId, GameTypeConfig cfg) {
        // TODO etap 2: klonowanie template world na unikalna nazwe instancji.
        throw new UnsupportedOperationException("TemplateCloneMapProvider is not implemented yet");
    }

    @Override
    public void reset(GameInstance instance) {
        // TODO etap 2: usuwanie sklonowanego swiata i odtworzenie instancji.
    }
}

