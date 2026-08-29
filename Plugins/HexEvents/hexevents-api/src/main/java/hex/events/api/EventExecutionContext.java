package hex.events.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record EventExecutionContext(
        UUID instanceId,
        String eventId,
        String displayName,
        Instant lobbyAt,
        Instant startAt,
        Instant endAt,
        EventModuleSettings settings,
        Set<UUID> registeredPlayers
) {
    public EventExecutionContext {
        settings = settings == null ? EventModuleSettings.empty() : settings;
        registeredPlayers = registeredPlayers == null ? Set.of() : Set.copyOf(registeredPlayers);
    }
}
