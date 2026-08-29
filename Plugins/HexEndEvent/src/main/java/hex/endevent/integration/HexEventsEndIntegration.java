package hex.endevent.integration;

import hex.endevent.service.EndEventService;
import hex.events.api.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class HexEventsEndIntegration implements EndEventGateway, AutoCloseable {
    private final Plugin plugin;
    private final EndEventService service;
    private final HexEventsApi api;
    private final ModuleRegistration registration;

    private HexEventsEndIntegration(Plugin plugin, EndEventService service, HexEventsApi api) {
        this.plugin = plugin; this.service = service; this.api = api;
        this.registration = api.registerModule(new EndModule());
        service.setGateway(this);
    }

    public static HexEventsEndIntegration attach(Plugin plugin, EndEventService service) {
        var apiReg = Bukkit.getServicesManager().getRegistration(HexEventsApi.class);
        if (apiReg == null) return null;
        return new HexEventsEndIntegration(plugin, service, apiReg.getProvider());
    }

    @Override public Optional<Window> next() { return api.nextEvent("end_opening").map(this::window); }
    @Override public Optional<Window> active() { return api.activeEvent("end_opening").map(this::window); }
    private Window window(EventInstanceView view) { return new Window(view.instanceId(), view.eventId(), view.displayName(), view.startAt(), view.endAt()); }
    @Override public void requestJoin(Player player, String source) {
        Optional<Window> target = active();
        if (target.isEmpty()) { service.notifyBlocked(player); return; }
        JoinSource joinSource;
        try { joinSource = JoinSource.valueOf(source); } catch (Exception ignored) { joinSource = JoinSource.WORLD_PORTAL; }
        api.requestJoin(player.getUniqueId(), target.get().instanceId(), joinSource).thenAccept(result -> {
            if (!result.success()) Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("§c" + result.message()));
        });
    }
    @Override public boolean isParticipant(UUID playerId) { return active().map(v -> api.isParticipant(playerId, v.instanceId())).orElse(false); }

    @Override public void close() { service.clearGateway(); registration.close(); }

    private final class EndModule implements HexEventModule {
        @Override public String moduleId() { return "hex:end"; }
        @Override public EventModuleCapabilities capabilities() { return new EventModuleCapabilities(false, true, true, true, true, false, false, false); }
        @Override public CompletionStage<PrepareResult> prepare(EventExecutionContext context) {
            return service.prepareExternal(context.instanceId(), context.startAt(), context.endAt()).thenApply(ok -> ok ? PrepareResult.ok() : PrepareResult.failed("End preparation failed"));
        }
        @Override public CompletionStage<StartResult> start(EventExecutionContext context) {
            return CompletableFuture.completedFuture(service.startExternal(context.instanceId(), context.startAt(), context.endAt()) ? StartResult.started() : StartResult.failed("End start rejected"));
        }
        @Override public EventJoinResult join(EventJoinRequest request) {
            Player player = Bukkit.getPlayer(request.playerId());
            if (player == null || !player.isOnline()) return EventJoinResult.denied("Gracz musi być online.");
            if (service.isParticipant(player.getUniqueId()) && player.getWorld().getEnvironment() == org.bukkit.World.Environment.THE_END) return EventJoinResult.alreadyJoined();
            return service.joinExternal(player) ? EventJoinResult.joined() : EventJoinResult.error("Nie udało się przenieść do Endu.");
        }
        @Override public void leave(UUID instanceId, UUID playerId, LeaveReason reason) { service.leaveParticipant(playerId); }
        @Override public CompletionStage<StopResult> stop(UUID instanceId, EventStopReason reason) { return service.stopExternal(instanceId).thenApply(ok -> ok ? StopResult.stopped() : StopResult.failed("End close failed")); }
        @Override public EventRuntimeSnapshot snapshot(UUID instanceId) { return new EventRuntimeSnapshot(true, Map.of("state", service.state().name(), "active", service.runtime().activeEventId())); }
    }
}
