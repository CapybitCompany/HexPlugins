package hex.events.api;

import java.time.Duration;

public record EventDefinitionView(
        String eventId,
        String displayName,
        String moduleId,
        Duration duration,
        boolean registrationRequired,
        int minPlayers,
        int maxPlayers
) { }
