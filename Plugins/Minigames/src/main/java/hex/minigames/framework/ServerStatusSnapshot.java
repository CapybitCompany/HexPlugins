package hex.minigames.framework;

public record ServerStatusSnapshot(
        int playersOnline,
        String motd,
        String state
) {
}

