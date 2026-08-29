package hex.events.api;

import java.time.Instant;
import java.util.UUID;

public record EventInstanceView(
        UUID instanceId,
        String eventId,
        String displayName,
        String moduleId,
        EventState state,
        EventAvailability availability,
        Instant registrationOpenAt,
        Instant lobbyAt,
        Instant startAt,
        Instant endAt,
        int registeredPlayers,
        int activeParticipants,
        int minPlayers,
        int maxPlayers
) { }
