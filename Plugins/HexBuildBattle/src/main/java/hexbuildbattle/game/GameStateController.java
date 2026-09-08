package hexbuildbattle.game;

import java.util.logging.Logger;

public final class GameStateController {

    private final Logger logger;

    public GameStateController(Logger logger) {
        this.logger = logger;
    }

    public void transition(GameSession session, GameState nextState) {
        GameState previous = session.state();
        if (previous == nextState) {
            return;
        }
        session.state(nextState);
        logger.info("Game state changed: " + previous + " -> " + nextState);
    }
}
