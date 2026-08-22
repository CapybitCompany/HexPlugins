package hex.endevent.service;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.endevent.config.EndEventConfig;
import hex.endevent.model.EndEventSlot;
import hex.endevent.model.EndEventState;
import hex.endevent.schedule.EndEventScheduleService;
import hex.endevent.state.EndEventRuntimeState;
import hex.endevent.state.RuntimeStateRepository;
import hex.endevent.ui.EndEventBossBarService;
import hex.endevent.util.TimeTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public final class EndEventService {
    private final Plugin plugin;
    private final HexApi hex;
    private volatile EndEventConfig config;
    private volatile EndEventScheduleService schedule;
    private final RuntimeStateRepository runtimeRepository;
    private final EndWorldResetService worldReset;
    private final EndEventBossBarService bossBar;
    private EndEventRuntimeState runtime;
    private volatile EndEventState state = EndEventState.CLOSED;
    private EndEventSlot openSlot;
    private String preparingEventId = "";
    private BukkitTask task;
    private boolean runtimeHealthy;
    private int bossBarAccumulatedTicks;

    public EndEventService(Plugin plugin, HexApi hex, EndEventConfig config) {
        this.plugin = plugin;
        this.hex = hex;
        this.config = config;
        this.schedule = new EndEventScheduleService(config);
        this.runtimeRepository = new RuntimeStateRepository(plugin, config.runtimeStateFile());
        RuntimeStateRepository.LoadResult loaded = runtimeRepository.load();
        this.runtime = loaded.state();
        this.runtimeHealthy = loaded.healthy();
        this.worldReset = new EndWorldResetService(plugin, config);
        this.bossBar = new EndEventBossBarService(hex, config);
    }

    public void start() {
        if (!runtimeHealthy) {
            setError("runtime.yml jest uszkodzony lub ma nieobslugiwana wersje");
        } else if (!config.enabled()) {
            state = EndEventState.DISABLED;
        } else {
            state = EndEventState.CLOSED;
            recoverIfPossible();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSafe, 20L, 20L);
        Bukkit.getScheduler().runTask(plugin, this::enforceOnlinePlayers);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        bossBar.hideAll();
        runtimeRepository.save(runtime);
    }

    public void reload(EndEventConfig newConfig) {
        if (state == EndEventState.PREPARING || state == EndEventState.CLOSING) {
            throw new IllegalStateException("Nie mozna przeladowac konfiguracji podczas PREPARING/CLOSING");
        }
        boolean wasOpen = state == EndEventState.OPEN;
        EndEventSlot previousOpenSlot = openSlot;
        EndEventConfig previousConfig = this.config;
        if (wasOpen && (!previousConfig.endWorld().equals(newConfig.endWorld())
                || !previousConfig.returnWorld().equals(newConfig.returnWorld())
                || !previousConfig.runtimeStateFile().equals(newConfig.runtimeStateFile()))) {
            throw new IllegalStateException("Nie mozna zmienic world.end-world, world.return-world ani runtime.state-file podczas aktywnego eventu");
        }

        this.config = newConfig;
        this.schedule = new EndEventScheduleService(newConfig);
        this.worldReset.reload(newConfig);
        this.bossBar.reload(newConfig);
        this.runtimeRepository.setFileName(newConfig.runtimeStateFile());

        RuntimeStateRepository.LoadResult reloadedRuntime = runtimeRepository.load();
        if (!reloadedRuntime.healthy()) {
            this.runtime = reloadedRuntime.state();
            this.runtimeHealthy = false;
            setError("runtime.yml jest uszkodzony lub ma nieobslugiwana wersje");
            return;
        }
        this.runtime = reloadedRuntime.state();
        this.runtimeHealthy = true;

        if (!newConfig.enabled()) {
            if (wasOpen || playersInEnd() > 0) {
                state = EndEventState.CLOSING;
                finishClose(previousOpenSlot, true);
                enforceOnlinePlayers();
                return;
            }
            state = EndEventState.DISABLED;
            enforceOnlinePlayers();
            return;
        }

        if (wasOpen && previousOpenSlot != null) {
            Optional<EndEventSlot> activeNow = schedule.activeSlot(schedule.now());
            if (activeNow.isPresent() && activeNow.get().eventId().equals(previousOpenSlot.eventId()) && isPreparedFor(activeNow.get())) {
                openSlot = activeNow.get();
                state = EndEventState.OPEN;
                bossBar.start(openSlot);
                enforceOnlinePlayers();
                return;
            }
            state = EndEventState.CLOSING;
            finishClose(previousOpenSlot, false);
            return;
        }

        if (state == EndEventState.DISABLED || state == EndEventState.ERROR_CLOSED) state = EndEventState.CLOSED;
        tickSafe();
        enforceOnlinePlayers();
    }

    private void recoverIfPossible() {
        ZonedDateTime now = schedule.now();
        Optional<EndEventSlot> active = schedule.activeSlot(now);
        if (active.isEmpty()) {
            if (!runtime.activeEventId().isBlank()) {
                runtime.activeEventId("");
                runtime.activeUntil("");
                runtime.resetRequired(true);
                runtimeRepository.save(runtime);
            }
            return;
        }
        EndEventSlot slot = active.get();
        if (isPreparedFor(slot) && worldReset.ensurePreparedWorldLoaded(runtime.generationSeed())) {
            openEvent(slot, false);
        }
    }

    private void tickSafe() {
        try {
            tick();
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.SEVERE, "Blad state machine HexEndEvent; End pozostaje zamkniety", throwable);
            setError("Wyjatek state machine: " + rootMessage(throwable));
        }
    }

    private void tick() {
        if (state == EndEventState.CLOSING) {
            finishClose(openSlot, !config.enabled());
            return;
        }
        if (!config.enabled()) {
            if (state != EndEventState.DISABLED && state != EndEventState.ERROR_CLOSED) state = EndEventState.DISABLED;
            bossBar.hideAll();
            return;
        }
        if (state == EndEventState.ERROR_CLOSED || state == EndEventState.PREPARING) return;

        ZonedDateTime now = schedule.now();

        if (state == EndEventState.OPEN && openSlot != null) {
            if (!now.isBefore(openSlot.end())) {
                beginClose(openSlot);
                return;
            }
            bossBarAccumulatedTicks += 20;
            if (bossBarAccumulatedTicks >= config.bossBar().updateIntervalTicks()) {
                bossBarAccumulatedTicks = 0;
                bossBar.tick(now);
            }
            return;
        }

        Optional<EndEventSlot> active = schedule.activeSlot(now);
        if (active.isPresent()) {
            EndEventSlot slot = active.get();
            if (isPreparedFor(slot)) {
                if (worldReset.ensurePreparedWorldLoaded(runtime.generationSeed())) openEvent(slot, true);
                else setError("Marker przygotowania istnieje, ale przygotowany End nie istnieje lub nie mozna go zaladowac");
            } else if (!slot.eventId().equals(runtime.lastFinishedEventId())) {
                beginPrepare(slot);
            }
            return;
        }

        EndEventSlot next = schedule.nextSlot(now);
        if (isPreparedFor(next)) {
            state = EndEventState.READY;
            return;
        }
        if (!now.isBefore(next.start().minus(config.prepareBefore()))) {
            beginPrepare(next);
        } else {
            state = EndEventState.CLOSED;
        }
    }

    private boolean isPreparedFor(EndEventSlot slot) {
        return !runtime.resetRequired()
                && slot.eventId().equals(runtime.preparedEventId())
                && slot.eventId().equals(runtime.generationEventId());
    }

    private void beginPrepare(EndEventSlot slot) {
        if (state == EndEventState.PREPARING || slot.eventId().equals(preparingEventId)) return;
        state = EndEventState.PREPARING;
        preparingEventId = slot.eventId();
        bossBar.hideAll();
        long seed = config.seedMode() == EndEventConfig.SeedMode.FIXED
                ? config.fixedSeed()
                : ThreadLocalRandom.current().nextLong();
        plugin.getLogger().info("Przygotowanie swiezego Endu dla eventu " + slot.eventId() + " (seed=" + seed + ")");

        worldReset.prepare(seed).whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            preparingEventId = "";
            if (throwable != null) {
                setError("Reset Endu zakonczyl sie wyjatkiem: " + rootMessage(throwable));
                return;
            }
            if (result == null || !result.success()) {
                setError(result == null ? "Reset Endu zwrocil pusty wynik" : result.error());
                return;
            }
            runtime.preparedEventId(slot.eventId());
            runtime.generationEventId(slot.eventId());
            runtime.generationSeed(seed);
            runtime.resetRequired(false);
            runtime.activeEventId("");
            runtime.activeUntil("");
            runtimeRepository.save(runtime);
            state = EndEventState.READY;
            plugin.getLogger().info("End gotowy dla eventu " + slot.eventId());

            ZonedDateTime now = schedule.now();
            if (slot.contains(now)) openEvent(slot, true);
        }));
    }

    private void openEvent(EndEventSlot slot, boolean announce) {
        if (!config.enabled() || !slot.contains(schedule.now())) return;
        if (!isPreparedFor(slot)) {
            setError("Proba otwarcia Endu bez poprawnego prepared-event-id");
            return;
        }
        boolean alreadyActive = slot.eventId().equals(runtime.activeEventId());
        this.openSlot = slot;
        this.state = EndEventState.OPEN;
        runtime.activeEventId(slot.eventId());
        runtime.activeUntil(slot.end().toInstant().toString());
        runtimeRepository.save(runtime);
        bossBarAccumulatedTicks = 0;
        bossBar.start(slot);
        if (announce && !alreadyActive) {
            hex.ui().broadcast("endevent.broadcast.open", UiTokens.of("duration", TimeTextFormatter.duration(config.duration())));
        }
        plugin.getLogger().info("End Event OPEN: " + slot.eventId() + " do " + slot.end());
    }

    private void beginClose(EndEventSlot slot) {
        state = EndEventState.CLOSING;
        bossBar.hideAll();
        finishClose(slot, false);
    }

    private void finishClose(EndEventSlot slot, boolean disabledByConfig) {
        boolean evacuated = worldReset.evictManagedEndPlayers();
        if (!evacuated) {
            plugin.getLogger().warning("Nie udalo sie jeszcze ewakuowac wszystkich graczy z Endu; ponawiam za sekunde.");
            state = EndEventState.CLOSING;
            return;
        }
        bossBar.hideAll();
        String finishedId = slot != null ? slot.eventId() : runtime.activeEventId();
        if (!finishedId.isBlank()) runtime.lastFinishedEventId(finishedId);
        runtime.activeEventId("");
        runtime.activeUntil("");
        runtime.resetRequired(true);
        runtime.preparedEventId("");
        runtimeRepository.save(runtime);
        openSlot = null;

        if (!worldReset.unloadManagedEndAfterClose()) {
            plugin.getLogger().warning("Nie udalo sie zwolnic Endu po zamknieciu. Zostanie ponownie unloadowany przed resetem.");
        }
        state = disabledByConfig ? EndEventState.DISABLED : EndEventState.CLOSED;
        if (!finishedId.isBlank()) hex.ui().broadcast("endevent.broadcast.closed");
        plugin.getLogger().info("End Event CLOSED" + (finishedId.isBlank() ? "" : ": " + finishedId));
    }

    private void setError(String reason) {
        bossBar.hideAll();
        state = EndEventState.ERROR_CLOSED;
        plugin.getLogger().severe("HexEndEvent ERROR_CLOSED: " + reason);
        enforceOnlinePlayers();
    }

    public boolean canEnter(Player player, World target) {
        if (target == null || target.getEnvironment() != World.Environment.THE_END) return true;
        return canEnterEnd(player);
    }

    public boolean canEnterEnd(Player player) {
        if (state == EndEventState.OPEN) return true;
        boolean resetting = state == EndEventState.PREPARING || state == EndEventState.CLOSING;
        return !resetting && player.hasPermission(config.bypassPermission());
    }

    public boolean shouldProtectTarget(World target) {
        if (target == null || target.getEnvironment() != World.Environment.THE_END) return false;
        return config.blockAllEndEnvironments() || target.getName().equals(config.endWorld());
    }

    public void notifyBlocked(Player player) {
        if (state == EndEventState.DISABLED) {
            hex.ui().send(player, "endevent.status.disabled");
            return;
        }
        hex.ui().send(player, "endevent.access.closed", UiTokens.of("next", nextOpenText()));
    }

    public void enforcePlayer(Player player, boolean notify) {
        if (player.getWorld().getEnvironment() != World.Environment.THE_END) return;
        if (canEnter(player, player.getWorld())) return;
        if (!worldReset.evictPlayer(player)) {
            plugin.getLogger().severe("Nie udalo sie ewakuowac gracza " + player.getName() + " z zamknietego Endu.");
            return;
        }
        bossBar.hide(player);
        if (notify) notifyBlocked(player);
        plugin.getLogger().warning("Backstop: ewakuowano " + player.getName() + " (" + player.getUniqueId() + ") z Endu przy stanie " + state);
    }

    public void enforceOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) enforcePlayer(player, false);
    }

    public void refreshBossBar(Player player) {
        if (state == EndEventState.OPEN) bossBar.refreshPlayer(player);
        else bossBar.hide(player);
    }

    public void hideBossBar(Player player) {
        bossBar.hide(player);
    }

    public void forceErrorClosed(String reason) {
        setError(reason);
    }

    public EndEventState state() { return state; }
    public EndEventConfig config() { return config; }
    public EndEventRuntimeState runtime() { return runtime; }
    public int playersInEnd() { return worldReset.playersInManagedEnd(); }
    public boolean managedEndLoaded() { return worldReset.isManagedEndLoaded(); }

    public Optional<EndEventSlot> activeSlot() {
        if (state == EndEventState.OPEN && openSlot != null) return Optional.of(openSlot);
        return schedule.activeSlot(schedule.now());
    }

    public EndEventSlot nextSlot() { return schedule.nextSlot(schedule.now()); }

    public String nextOpenText() {
        if (!config.enabled()) return "event wyłączony";
        return TimeTextFormatter.friendly(schedule.nextSlot(schedule.now()).start());
    }

    public String nextOpenPlaceholder() {
        if (!config.enabled()) return "-";
        return TimeTextFormatter.dateTime(schedule.nextSlot(schedule.now()).start());
    }

    public String nextOpenDate() {
        if (!config.enabled()) return "-";
        return TimeTextFormatter.date(schedule.nextSlot(schedule.now()).start());
    }

    public String nextOpenTime() {
        if (!config.enabled()) return "-";
        return TimeTextFormatter.time(schedule.nextSlot(schedule.now()).start());
    }

    public String nextOpenRelative() {
        if (!config.enabled()) return "-";
        ZonedDateTime now = schedule.now();
        return TimeTextFormatter.relative(now, schedule.nextSlot(now).start());
    }

    public String remainingText() {
        if (state != EndEventState.OPEN || openSlot == null) return "-";
        return TimeTextFormatter.remaining(schedule.now(), openSlot.end());
    }

    public String closesAtText() {
        return state == EndEventState.OPEN && openSlot != null ? TimeTextFormatter.time(openSlot.end()) : "-";
    }

    public String statusText() {
        return switch (state) {
            case DISABLED -> "WYŁĄCZONY";
            case CLOSED -> "ZAMKNIĘTY";
            case PREPARING -> "PRZYGOTOWANIE";
            case READY -> "GOTOWY";
            case OPEN -> "OTWARTY";
            case CLOSING -> "ZAMYKANIE";
            case ERROR_CLOSED -> "BŁĄD/ZAMKNIĘTY";
        };
    }

    public boolean isOpen() { return state == EndEventState.OPEN; }

    public void sendStatus(org.bukkit.command.CommandSender sender) {
        switch (state) {
            case DISABLED -> hex.ui().send(sender, "endevent.status.disabled");
            case OPEN -> hex.ui().send(sender, "endevent.status.open", UiTokens.of("remaining", remainingText()).put("closes", closesAtText()));
            case PREPARING -> hex.ui().send(sender, "endevent.status.preparing", UiTokens.of("next", nextOpenText()));
            case ERROR_CLOSED -> hex.ui().send(sender, "endevent.error.unavailable");
            default -> hex.ui().send(sender, "endevent.status.closed", UiTokens.of("next", nextOpenText()));
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
