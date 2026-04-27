package hex.minigames.games.spleef;

import hex.minigames.framework.GameBehaviour;
import hex.minigames.framework.GameInstance;

public final class SpleefBehaviour implements GameBehaviour {

    @Override
    public void onGameStart(GameInstance instance) {
        instance.broadcastTemplate("game.spleef.start");
    }

    @Override
    public void onGameEnd(GameInstance instance) {
        instance.broadcastTemplate("game.spleef.end");
    }
}

