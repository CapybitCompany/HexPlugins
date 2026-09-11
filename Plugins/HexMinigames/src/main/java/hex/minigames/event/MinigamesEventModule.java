package hex.minigames.event;

import hex.events.api.EventAvailability;
import hex.events.api.EventExecutionContext;
import hex.events.api.EventJoinRequest;
import hex.events.api.EventJoinResult;
import hex.events.api.EventModuleCapabilities;
import hex.events.api.EventModuleSettings;
import hex.events.api.EventRuntimeSnapshot;
import hex.events.api.EventStopReason;
import hex.events.api.HexEventModule;
import hex.events.api.LeaveReason;
import hex.events.api.PrepareResult;
import hex.events.api.StartResult;
import hex.events.api.StopResult;
import hex.minigames.runtime.MinigamesSessionService;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class MinigamesEventModule implements HexEventModule {
    public static final String MODULE_ID = "hex:minigames";

    private final MinigamesSessionService sessions;

    public MinigamesEventModule(MinigamesSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public EventModuleCapabilities capabilities() {
        return new EventModuleCapabilities(true, false, true, false, true, true, false, false);
    }

    @Override
    public EventAvailability availability(EventModuleSettings settings) {
        return sessions.available() ? EventAvailability.AVAILABLE : EventAvailability.MISCONFIGURED;
    }

    @Override
    public String availabilityReason(EventModuleSettings settings) {
        return sessions.availabilityReason();
    }

    @Override
    public CompletionStage<PrepareResult> prepare(EventExecutionContext context) {
        String failure = sessions.prepareFailure(context);
        return CompletableFuture.completedFuture(failure == null ? PrepareResult.ok() : PrepareResult.failed(failure));
    }

    @Override
    public CompletionStage<StartResult> start(EventExecutionContext context) {
        return CompletableFuture.completedFuture(sessions.startEvent(context));
    }

    @Override
    public EventJoinResult join(EventJoinRequest request) {
        return sessions.joinFromHexEvents(request);
    }

    @Override
    public void leave(UUID instanceId, UUID playerId, LeaveReason reason) {
        sessions.leaveFromHexEvents(instanceId, playerId, reason);
    }

    @Override
    public CompletionStage<StopResult> stop(UUID instanceId, EventStopReason reason) {
        sessions.stopEvent(instanceId, reason);
        return CompletableFuture.completedFuture(StopResult.stopped());
    }

    @Override
    public EventRuntimeSnapshot snapshot(UUID instanceId) {
        return new EventRuntimeSnapshot(true, Map.of(
                "state", sessions.stateName(instanceId),
                "activePlayers", String.valueOf(sessions.activeCount(instanceId))
        ));
    }
}
