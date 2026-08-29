package hex.events.api;

import hex.events.lifecycle.EventLifecycleService;
import hex.events.registry.EventModuleRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class HexEventsApiImpl implements HexEventsApi {
    private final Plugin plugin;
    private final EventModuleRegistry modules;
    private final EventLifecycleService lifecycle;

    public HexEventsApiImpl(Plugin plugin, EventModuleRegistry modules, EventLifecycleService lifecycle) {
        this.plugin = plugin; this.modules = modules; this.lifecycle = lifecycle;
    }

    @Override public ModuleRegistration registerModule(HexEventModule module) { return onMainSync(() -> modules.register(module)); }
    @Override public Optional<EventInstanceView> instance(UUID instanceId) { return onMainSync(() -> lifecycle.instance(instanceId).map(i -> i.view(lifecycle.availability(i)))); }
    @Override public Optional<EventInstanceView> nextEvent() { return onMainSync(() -> lifecycle.nextEvent().map(i -> i.view(lifecycle.availability(i)))); }
    @Override public Optional<EventInstanceView> nextEvent(String eventId) { return onMainSync(() -> lifecycle.nextEvent(eventId).map(i -> i.view(lifecycle.availability(i)))); }
    @Override public Optional<EventInstanceView> activeEvent(String eventId) { return onMainSync(() -> lifecycle.activeEvent(eventId).map(i -> i.view(lifecycle.availability(i)))); }
    @Override public List<EventInstanceView> upcoming(int days) { return onMainSync(() -> lifecycle.upcomingDays(days, lifecycle.engineConfig().displayZone()).stream().map(i -> i.view(lifecycle.availability(i))).toList()); }
    @Override public boolean isRegistered(UUID playerId, UUID instanceId) { return onMainSync(() -> lifecycle.instance(instanceId).map(i -> i.registeredPlayers().contains(playerId)).orElse(false)); }
    @Override public boolean isParticipant(UUID playerId, UUID instanceId) { return onMainSync(() -> lifecycle.instance(instanceId).map(i -> i.participants().contains(playerId)).orElse(false)); }

    @Override
    public CompletableFuture<EventJoinResult> requestJoin(UUID playerId, UUID instanceId, JoinSource source) {
        CompletableFuture<EventJoinResult> future = new CompletableFuture<>();
        Runnable task = () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) { future.complete(EventJoinResult.denied("Gracz musi być online.")); return; }
            try { future.complete(lifecycle.requestJoin(player, instanceId, source)); }
            catch (Throwable error) { future.completeExceptionally(error); }
        };
        if (Bukkit.isPrimaryThread()) task.run(); else Bukkit.getScheduler().runTask(plugin, task);
        return future;
    }

    @Override public boolean complete(UUID instanceId, EventResult result) { return onMainSync(() -> lifecycle.complete(instanceId, result)); }
    @Override public boolean fail(UUID instanceId, EventFailure failure) { return onMainSync(() -> lifecycle.fail(instanceId, failure)); }

    /** All lifecycle runtime state is owned by the Bukkit primary thread. */
    private <T> T onMainSync(Supplier<T> work) {
        if (Bukkit.isPrimaryThread()) return work.get();
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { future.complete(work.get()); }
            catch (Throwable error) { future.completeExceptionally(error); }
        });
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new IllegalStateException("HexEvents API main-thread dispatch failed", error);
        }
    }
}
