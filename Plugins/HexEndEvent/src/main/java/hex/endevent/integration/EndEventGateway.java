package hex.endevent.integration;

import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EndEventGateway {
    record Window(UUID instanceId, String eventId, String name, Instant startAt, Instant endAt) { }
    Optional<Window> next();
    Optional<Window> active();
    void requestJoin(Player player, String source);
    boolean isParticipant(UUID playerId);
}
