package hex.events.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface HexEventModule {
    String moduleId();
    EventModuleCapabilities capabilities();

    default EventAvailability availability(EventModuleSettings settings) { return EventAvailability.AVAILABLE; }
    default String availabilityReason(EventModuleSettings settings) { return ""; }

    default CompletionStage<PrepareResult> prepare(EventExecutionContext context) {
        return CompletableFuture.completedFuture(PrepareResult.ok());
    }

    CompletionStage<StartResult> start(EventExecutionContext context);

    EventJoinResult join(EventJoinRequest request);

    default void leave(UUID instanceId, UUID playerId, LeaveReason reason) { }

    CompletionStage<StopResult> stop(UUID instanceId, EventStopReason reason);

    default EventRuntimeSnapshot snapshot(UUID instanceId) { return EventRuntimeSnapshot.unavailable(); }
}
