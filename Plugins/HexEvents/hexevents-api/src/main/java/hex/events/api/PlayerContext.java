package hex.events.api;

import java.util.UUID;

public record PlayerContext(UUID playerId, String playerName) { }
