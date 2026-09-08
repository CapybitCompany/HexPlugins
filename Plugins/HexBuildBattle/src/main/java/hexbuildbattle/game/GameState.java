package hexbuildbattle.game;

public enum GameState {
    WAITING,
    COUNTDOWN,
    THEME_VOTING,
    BUILDING,
    PRE_JUDGING,
    JUDGING,
    RESULTS,
    RESETTING;

    public boolean acceptsQueueForCurrentRound() {
        return this == WAITING || this == COUNTDOWN;
    }

    public boolean isActiveRound() {
        return this != WAITING && this != COUNTDOWN;
    }
}
