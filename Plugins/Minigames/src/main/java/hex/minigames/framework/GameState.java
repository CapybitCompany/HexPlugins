package hex.minigames.framework;

public enum GameState {
    LOBBY,
    COUNTDOWN,
    INGAME,
    END,
    RESET;

    public boolean isJoinable() {
        return this == LOBBY || this == COUNTDOWN;
    }
}

