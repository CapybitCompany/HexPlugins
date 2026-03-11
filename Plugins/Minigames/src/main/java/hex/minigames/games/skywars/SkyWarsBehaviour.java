package hex.minigames.games.skywars;

import hex.minigames.framework.GameBehaviour;
import hex.minigames.framework.GameInstance;

public final class SkyWarsBehaviour implements GameBehaviour {

    @Override
    public void onGameStart(GameInstance instance) {
        instance.broadcastTemplate("game.skywars.start");
    }

    @Override
    public void onGameEnd(GameInstance instance) {
        instance.broadcastTemplate("game.skywars.end");
    }
}

