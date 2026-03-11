package hex.minigames.framework.map;

import hex.minigames.framework.GameInstance;
import hex.minigames.framework.config.GameTypeConfig;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class SingleWorldMapProvider implements MapProvider {

    @Override
    public String pickMap(String gameTypeId, GameTypeConfig cfg) {
        List<String> maps = cfg.maps();
        int idx = ThreadLocalRandom.current().nextInt(maps.size());
        return maps.get(idx);
    }

    @Override
    public void reset(GameInstance instance) {
        // Etap 1: bez klonowania swiatow, reset to tylko wyczyszczenie stanu instancji.
    }
}

