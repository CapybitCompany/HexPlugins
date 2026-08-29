package hex.events.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface HexEventsApi {
    ModuleRegistration registerModule(HexEventModule module);
    Optional<EventInstanceView> instance(UUID instanceId);
    Optional<EventInstanceView> nextEvent();
    Optional<EventInstanceView> nextEvent(String eventId);
    Optional<EventInstanceView> activeEvent(String eventId);
    List<EventInstanceView> upcoming(int days);
    boolean isRegistered(UUID playerId, UUID instanceId);
    boolean isParticipant(UUID playerId, UUID instanceId);
    CompletableFuture<EventJoinResult> requestJoin(UUID playerId, UUID instanceId, JoinSource source);
    boolean complete(UUID instanceId, EventResult result);
    boolean fail(UUID instanceId, EventFailure failure);
}
