package hex.events.api;

import java.util.UUID;

public record EventJoinRequest(
        UUID instanceId,
        UUID playerId,
        String playerName,
        JoinSource source,
        EventExecutionContext context
) { }
