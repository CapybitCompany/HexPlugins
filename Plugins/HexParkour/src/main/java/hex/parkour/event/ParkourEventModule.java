package hex.parkour.event;

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
import hex.parkour.service.ParkourSessionService;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ParkourEventModule implements HexEventModule {
    public static final String MODULE_ID = "hex:parkour";

    private final ParkourSessionService sessions;

    public ParkourEventModule(ParkourSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public EventModuleCapabilities capabilities() {
        return new EventModuleCapabilities(true, true, true, false, true, false, false, false);
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
        return CompletableFuture.completedFuture(sessions.available()
                ? PrepareResult.ok()
                : PrepareResult.failed(sessions.availabilityReason()));
    }

    @Override
    public CompletionStage<StartResult> start(EventExecutionContext context) {
        sessions.startInstance(context);
        return CompletableFuture.completedFuture(sessions.available()
                ? StartResult.started()
                : StartResult.failed(sessions.availabilityReason()));
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
        sessions.stopInstance(instanceId, reason);
        return CompletableFuture.completedFuture(StopResult.stopped());
    }

    @Override
    public EventRuntimeSnapshot snapshot(UUID instanceId) {
        return new EventRuntimeSnapshot(true, Map.of("activePlayers", String.valueOf(sessions.activeCount(instanceId))));
    }
}
