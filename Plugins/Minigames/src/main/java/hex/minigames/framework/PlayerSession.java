package hex.minigames.framework;

import java.util.UUID;

public record PlayerSession(UUID playerId, String gameTypeId, String instanceId) {
}

