package hex.minigames.framework;

public record ModeStatusSnapshot(
        String gameTypeId,
        int waiting,
        int inGame,
        boolean joinable,
        int maxPlayers
) {
}

