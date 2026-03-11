package hex.minigames.framework.config;

import java.util.List;

public record GameTypeConfig(
        int minPlayers,
        int maxPlayers,
        int countdownSeconds,
        int maxInstances,
        List<String> maps,
        LobbySpawn lobbySpawn
) {
}

