package hex.events.lifecycle;

import hex.events.api.*;
import hex.events.config.EngineConfig;
import hex.events.config.EventsConfig;
import hex.events.config.EventsConfigLoader;
import hex.events.model.EventDefinition;
import hex.events.model.EventInstance;
import hex.events.persistence.AdmissionRepository;
import hex.events.persistence.EventInstanceRepository;
import hex.events.persistence.EventSchedule7dRepository;
import hex.events.persistence.RegistrationRepository;
import hex.events.persistence.PersistenceExecutor;
import hex.events.registration.*;
import hex.events.registry.CostProviderRegistry;
import hex.events.registry.EventModuleRegistry;
import hex.events.registry.RequirementProviderRegistry;
import hex.events.registry.RewardProviderRegistry;
import hex.events.reward.RewardService;
import hex.events.schedule.EventOccurrenceCompiler;
import hex.events.schedule.EventScheduler;
import hex.events.schedule.ScheduledTransition;
import hex.events.ui.EventCountdownBossBarService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.*;
import java.util.*;
import java.util.logging.Level;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class EventLifecycleService {
    private final Plugin plugin;
    private final Clock clock;
    private final EventModuleRegistry modules;
    private final RequirementProviderRegistry requirementProviders;
    private final CostProviderRegistry costProviders;
    private final RewardProviderRegistry rewardProviders;
    private final RewardService rewardService;
    private final Set<UUID> completionInFlight = new HashSet<>();
    private final EventInstanceRepository instanceRepository;
    private final EventSchedule7dRepository schedule7dRepository;
    private final RegistrationRepository registrationRepository;
    private final AdmissionRepository admissionRepository;
    private final RegistrationService registrations;
    private final AdmissionService admissions;
    private final PersistenceExecutor persistence;
    private final EventCountdownBossBarService countdownBossBars;
    private final EventOccurrenceCompiler compiler = new EventOccurrenceCompiler();
    private final EventScheduler scheduler;
    private final Map<UUID, EventInstance> instances = new LinkedHashMap<>();
    private final Map<UUID, Instant> moduleWaitStartedAt = new HashMap<>();
    private static final Duration MODULE_STARTUP_GRACE = Duration.ofSeconds(10);
    private static final String PUBLIC_SCHEDULE_LANE = "public-schedule-7d";
    private volatile EventsConfig eventsConfig = new EventsConfig(Map.of());
    private volatile EngineConfig engineConfig;
    private volatile Instant compiledUntil;
    private BukkitTask calendarMaintenanceTask;
    private BukkitTask scheduleWindowMaintenanceTask;
    private volatile LocalDate publishedScheduleDate;

    public EventLifecycleService(Plugin plugin, Clock clock, EngineConfig engineConfig,
                                 EventModuleRegistry modules, RequirementProviderRegistry requirementProviders,
                                 CostProviderRegistry costProviders, RewardProviderRegistry rewardProviders, EventInstanceRepository instanceRepository,
                                 EventSchedule7dRepository schedule7dRepository, RegistrationRepository registrationRepository, AdmissionRepository admissionRepository,
                                 RegistrationService registrations, AdmissionService admissions, PersistenceExecutor persistence, RewardService rewardService,
                                 EventCountdownBossBarService countdownBossBars) {
        this.plugin = plugin;
        this.clock = clock;
        this.engineConfig = engineConfig;
        this.modules = modules;
        this.requirementProviders = requirementProviders;
        this.costProviders = costProviders;
        this.rewardProviders = rewardProviders;
        this.rewardService = rewardService;
        this.instanceRepository = instanceRepository;
        this.schedule7dRepository = schedule7dRepository;
        this.registrationRepository = registrationRepository;
        this.admissionRepository = admissionRepository;
        this.registrations = registrations;
        this.admissions = admissions;
        this.persistence = persistence;
        this.countdownBossBars = countdownBossBars;
        this.scheduler = new EventScheduler(plugin, clock, this::handleTransition);
        this.registrations.setObserver(new RegistrationObserver() {
            @Override public void onRegistered(Player player, EventInstance instance, EventQueuePriority priority) {
                admissions.onRegistered(instance, player, priority);
                if (admissions.isOpen(instance)) fillCapacity(instance);
            }
            @Override public void onCancelled(Player player, EventInstance instance) {
                admissions.onCancelled(instance, player.getUniqueId());
            }
            @Override public boolean canCancel(Player player, EventInstance instance) {
                return admissions.canCancel(instance, player.getUniqueId());
            }
        });
    }

    public CompletableFuture<Void> initializeAsync(EventsConfig config) {
        this.eventsConfig = config;
        return persistence.read(() -> {
                    List<EventInstanceRepository.StoredInstance> stored = instanceRepository.loadNonTerminal();
                    List<UUID> ids = stored.stream().map(EventInstanceRepository.StoredInstance::id).toList();
                    List<RegistrationRepository.RegistrationRow> registrations = registrationRepository.loadActiveForInstances(ids);
                    List<RegistrationRepository.ParticipantRow> participants = registrationRepository.loadActiveParticipants(ids);
                    List<AdmissionRepository.AdmissionRow> admissionRows = admissionRepository.loadForInstances(ids);
                    return new RestoreData(stored, registrations, participants, admissionRows);
                })
                .thenCompose(data -> onMainFuture(() -> {
                    applyStoredInstances(data);
                    rebuildCalendar(false);
                    recoverRuntimeInstances();
                    startCalendarMaintenance();
                    startScheduleWindowMaintenance();
                    return null;
                }))
                .thenCompose(ignored -> refreshPublishedScheduleAsync());
    }

    public void reload(EngineConfig newEngine, EventsConfig newEvents) {
        this.engineConfig = newEngine;
        this.eventsConfig = newEvents;
        rebuildCalendar(true);
        startCalendarMaintenance();
        startScheduleWindowMaintenance();
        refreshPublishedScheduleAsync().whenComplete((ignored, error) -> {
            if (error != null) plugin.getLogger().log(Level.SEVERE, "Nie udało się opublikować 7-dniowego harmonogramu po reloadzie.", error);
        });
    }

    public void shutdown() {
        scheduler.stop();
        if (calendarMaintenanceTask != null) {
            calendarMaintenanceTask.cancel();
            calendarMaintenanceTask = null;
        }
        if (scheduleWindowMaintenanceTask != null) {
            scheduleWindowMaintenanceTask.cancel();
            scheduleWindowMaintenanceTask = null;
        }
    }

    private void applyStoredInstances(RestoreData data) {
        instances.clear();
        for (EventInstanceRepository.StoredInstance row : data.instances()) {
            EventDefinition def = null;
            try {
                def = EventsConfigLoader.parseSnapshot(row.eventId(), row.snapshot());
            } catch (Exception snapshotError) {
                def = eventsConfig.definitions().get(row.eventId());
                if (def != null) plugin.getLogger().warning("Instancja " + row.id() + " ma nieczytelny snapshot; używam bieżącej definicji: " + snapshotError.getMessage());
            }
            if (def == null) {
                plugin.getLogger().severe("Nie można odtworzyć niezakończonej instancji " + row.id() + " / " + row.eventId() + ": brak poprawnego snapshotu i bieżącej definicji.");
                continue;
            }
            EventInstance i = new EventInstance(row.id(), def, Instant.ofEpochMilli(row.occurrenceAt()),
                    Instant.ofEpochMilli(row.registrationOpenAt()), Instant.ofEpochMilli(row.prepareAt()),
                    Instant.ofEpochMilli(row.lobbyAt()), Instant.ofEpochMilli(row.startAt()),
                    Instant.ofEpochMilli(row.lateJoinCloseAt()), Instant.ofEpochMilli(row.endAt()), row.state());
            i.prepared(row.prepared());
            i.lastError(row.lastError());
            instances.put(i.id(), i);
        }
        for (RegistrationRepository.RegistrationRow row : data.registrations()) {
            EventInstance i = instances.get(row.instanceId());
            if (i != null) i.rememberRegistration(row.playerId(), row.playerName(), row.registeredAt());
        }
        for (RegistrationRepository.ParticipantRow row : data.participants()) {
            EventInstance i = instances.get(row.instanceId());
            if (i != null) i.participants().add(row.playerId());
        }
        admissions.restore(instances.values(), data.admissions());
    }

    /** Atomic runtime calendar rebuild + reconciliation of stale future DB occurrences. */
    private void rebuildCalendar(boolean reload) {
        Instant now = clock.instant();
        Instant horizon = now.plus(Duration.ofDays(engineConfig.calendarDaysAhead()));
        Map<UUID, EventInstance> candidates = new LinkedHashMap<>();
        for (EventDefinition def : eventsConfig.definitions().values()) {
            for (EventInstance candidate : compiler.compileBetween(def, now.minus(Duration.ofDays(1)), horizon)) {
                candidates.put(candidate.id(), candidate);
            }
        }

        Map<UUID, EventInstance> next = new LinkedHashMap<>();
        for (EventInstance old : List.copyOf(instances.values())) {
            if (old.state().terminal()) continue;
            EventInstance candidate = candidates.remove(old.id());
            boolean future = old.startAt().isAfter(now);
            boolean frozen = old.state() != EventState.SCHEDULED || !old.registeredPlayers().isEmpty();

            if (candidate == null && future) {
                cancelForConfigChange(old);
                continue;
            }
            if (candidate != null && !frozen) {
                // A countdown may already be visible for this future occurrence. Recreate it
                // from the new immutable definition after scheduler.replace().
                countdownBossBars.hide(old.id());
                next.put(candidate.id(), candidate);
                EventInstanceRepository.InstanceSnapshot scheduledSnapshot = EventInstanceRepository.snapshot(candidate);
                persistence.fireAndForget(instanceLane(candidate.id()), "update-scheduled:" + candidate.id(), () -> instanceRepository.updateScheduledDefinition(scheduledSnapshot));
                continue;
            }
            // Frozen snapshot survives reload when the same occurrence still exists, and active runtime survives config removal.
            next.put(old.id(), old);
        }

        for (EventInstance candidate : candidates.values()) {
            if (candidate.endAt().isBefore(now.minusSeconds(60))) continue;
            next.put(candidate.id(), candidate);
            EventInstanceRepository.InstanceSnapshot insertSnapshot = EventInstanceRepository.snapshot(candidate);
            persistence.fireAndForget(instanceLane(candidate.id()), "insert-instance:" + candidate.id(), () -> instanceRepository.insertIfAbsent(insertSnapshot));
        }

        instances.clear();
        instances.putAll(next);
        List<ScheduledTransition> transitions = new ArrayList<>();
        for (EventInstance i : instances.values()) if (!i.state().terminal()) transitions.addAll(compiler.transitions(i));
        scheduler.replace(transitions, engineConfig.schedulerPeriodTicks());
        compiledUntil = horizon;
    }

    private void cancelForConfigChange(EventInstance i) {
        countdownBossBars.hide(i.id());
        try {
            if (!i.registeredPlayers().isEmpty()) registrations.refundAllAsync(i).exceptionally(error -> { plugin.getLogger().log(Level.SEVERE, "Błąd refundu podczas reconciliation " + i.id(), error); return null; });
        } catch (Throwable error) {
            plugin.getLogger().log(Level.SEVERE, "Błąd refundu podczas reconciliation " + i.id(), error);
        }
        admissions.clear(i);
        if (i.state() == EventState.PREPARING || i.state() == EventState.LOBBY) {
            modules.find(i.definition().moduleId()).ifPresent(module -> {
                try { module.stop(i.id(), EventStopReason.ADMIN_STOP); }
                catch (Throwable ignored) { }
            });
        }
        i.state(EventState.CANCELLED);
        i.lastError("CANCELLED_CONFIG_CHANGED");
        UUID cancelledId = i.id();
        persistence.fireAndForget(instanceLane(cancelledId), "cancel-config:" + cancelledId, () -> instanceRepository.cancelForConfigChange(cancelledId, "CANCELLED_CONFIG_CHANGED"));
    }

    private void startCalendarMaintenance() {
        if (calendarMaintenanceTask != null) calendarMaintenanceTask.cancel();
        calendarMaintenanceTask = Bukkit.getScheduler().runTaskTimer(plugin, this::maintainCalendar,
                engineConfig.calendarMaintenanceTicks(), engineConfig.calendarMaintenanceTicks());
    }

    /**
     * Keeps the public 7-day DB snapshot aligned with the same day window as the
     * in-game GUI (today + 6 days). The timer only checks the local date in RAM;
     * it touches the database only when the calendar day changes.
     */
    private void startScheduleWindowMaintenance() {
        if (scheduleWindowMaintenanceTask != null) scheduleWindowMaintenanceTask.cancel();
        scheduleWindowMaintenanceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            LocalDate today = clock.instant().atZone(engineConfig.displayZone()).toLocalDate();
            if (Objects.equals(today, publishedScheduleDate)) return;
            refreshPublishedScheduleAsync().whenComplete((ignored, error) -> {
                if (error != null) plugin.getLogger().log(Level.SEVERE, "Nie udało się odświeżyć 7-dniowego harmonogramu po zmianie dnia.", error);
            });
        }, 20L * 60L, 20L * 60L);
    }

    /**
     * Publishes an atomically replaced, denormalized schedule for external API
     * consumers. Rows are built on the Bukkit thread from runtime/cache only;
     * the JDBC transaction is executed asynchronously in its own persistence lane.
     */
    private CompletableFuture<Void> refreshPublishedScheduleAsync() {
        return onMainFuture(this::buildPublishedScheduleSnapshot)
                .thenCompose(snapshot -> persistence.write(PUBLIC_SCHEDULE_LANE,
                                () -> schedule7dRepository.replaceAll(snapshot.rows(), snapshot.publishedAt(), snapshot.zone()))
                        .thenRun(() -> publishedScheduleDate = snapshot.windowDate()))
                .whenComplete((ignored, error) -> {
                    if (error != null) publishedScheduleDate = null;
                });
    }

    private PublishedScheduleSnapshot buildPublishedScheduleSnapshot() {
        ZoneId zone = engineConfig.displayZone();
        LocalDate from = clock.instant().atZone(zone).toLocalDate();
        LocalDate to = from.plusDays(6);
        List<EventSchedule7dRepository.ScheduleRow> rows = new ArrayList<>();

        for (EventInstance instance : instances.values()) {
            if (instance.state().terminal()) continue;
            LocalDate eventDate = instance.occurrenceAt().atZone(zone).toLocalDate();
            if (eventDate.isBefore(from) || eventDate.isAfter(to)) continue;
            EventDefinition.ScheduleKind scheduleKind = instance.definition().schedule().kindAt(instance.occurrenceAt());
            boolean recurring = scheduleKind == EventDefinition.ScheduleKind.RECURRING;
            String scheduleType = scheduleKind.name();
            LocalDateTime scheduledAt = instance.occurrenceAt().atZone(zone).toLocalDateTime().withNano(0);
            rows.add(new EventSchedule7dRepository.ScheduleRow(
                    instance.id(),
                    instance.definition().id(),
                    plainEventName(instance.definition().displayName()),
                    scheduledAt,
                    instance.occurrenceAt().getEpochSecond(),
                    zone.getId(),
                    scheduleType,
                    recurring
            ));
        }

        rows.sort(Comparator
                .comparing(EventSchedule7dRepository.ScheduleRow::scheduledAt)
                .thenComparing(EventSchedule7dRepository.ScheduleRow::eventId)
                .thenComparing(row -> row.instanceId().toString()));
        return new PublishedScheduleSnapshot(List.copyOf(rows), from, zone, clock.instant());
    }

    private static String plainEventName(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String legacy = ChatColor.translateAlternateColorCodes('&', raw);
        String stripped = ChatColor.stripColor(legacy);
        if (stripped == null) stripped = raw;
        // Event names currently use legacy colors, but stripping simple MiniMessage
        // tags as well makes the public table safer for future website consumption.
        return stripped.replaceAll("<[^>]+>", "").trim();
    }

    private void maintainCalendar() {
        Instant now = clock.instant();
        Instant desired = now.plus(Duration.ofDays(engineConfig.calendarDaysAhead()));
        Instant from = compiledUntil == null ? now : compiledUntil;
        if (!from.isBefore(desired.minusSeconds(60))) return;

        List<ScheduledTransition> addedTransitions = new ArrayList<>();
        int added = 0;
        for (EventDefinition def : eventsConfig.definitions().values()) {
            for (EventInstance candidate : compiler.compileBetween(def, from.plusMillis(1), desired)) {
                if (instances.containsKey(candidate.id())) continue;
                instances.put(candidate.id(), candidate);
                EventInstanceRepository.InstanceSnapshot insertSnapshot = EventInstanceRepository.snapshot(candidate);
                persistence.fireAndForget(instanceLane(candidate.id()), "insert-instance:" + candidate.id(), () -> instanceRepository.insertIfAbsent(insertSnapshot));
                addedTransitions.addAll(compiler.transitions(candidate));
                added++;
            }
        }
        scheduler.add(addedTransitions);
        compiledUntil = desired;
        if (engineConfig.debug() && added > 0) plugin.getLogger().info("CalendarMaintenance: added occurrences=" + added + ", compiledUntil=" + compiledUntil);
    }

    private void recoverRuntimeInstances() {
        Instant now = clock.instant();
        for (EventInstance i : List.copyOf(instances.values())) {
            if (i.state().terminal()) continue;
            if (!i.endAt().isAfter(now)) {
                if (admissions.isOpen(i)) admissions.close(i);
                finishWithoutModule(i, EventState.FINISHED, "Recovered after scheduled end");
                continue;
            }
            if (i.state() == EventState.LOBBY) {
                if (usesAdmissionQueue(i)) {
                    admissions.open(i);
                    fillCapacity(i);
                }
            } else if (i.state() == EventState.RUNNING) {
                recoverRunning(i);
            } else if (i.state() == EventState.FINISHING) {
                recoverFinishing(i);
            }
        }
    }

    private void recoverFinishing(EventInstance i) {
        HexEventModule module = modules.find(i.definition().moduleId()).orElse(null);
        if (module == null) {
            if (withinModuleStartupGrace(i.id())) Bukkit.getScheduler().runTaskLater(plugin, () -> recoverFinishing(i), 20L);
            else finishWithoutModule(i, EventState.FINISHED, "Recovered FINISHING without module");
            return;
        }
        clearModuleWait(i.id());
        module.stop(i.id(), EventStopReason.SERVER_SHUTDOWN).whenComplete((result,error) -> onMain(() -> {
            if (error != null || result == null || !result.success()) {
                fail(i.id(), new EventFailure("FINISH_RECOVERY_FAILED", error == null ? (result == null ? "null result" : result.message()) : rootMessage(error), false));
                return;
            }
            finishWithoutModule(i, EventState.FINISHED, "");
            persistence.barrier(instanceLane(i.id())).whenComplete((v,e) -> { if(e==null) rewardService.deliverForInstance(i.id()); });
        }));
    }

    private void recoverRunning(EventInstance i) {
        if (i.state().terminal() || i.state() != EventState.RUNNING) return;
        HexEventModule module = modules.find(i.definition().moduleId()).orElse(null);
        if (module == null) {
            if (withinModuleStartupGrace(i.id())) Bukkit.getScheduler().runTaskLater(plugin, () -> recoverRunning(i), 20L);
            else fail(i.id(), new EventFailure("MODULE_UNAVAILABLE", "Moduł " + i.definition().moduleId() + " nie zarejestrował się w czasie startu serwera.", true));
            return;
        }
        clearModuleWait(i.id());
        if (!module.capabilities().supportsRecovery()) {
            fail(i.id(), new EventFailure("RECOVERY_UNSUPPORTED", "Moduł nie wspiera recovery aktywnego eventu.", false));
            return;
        }
        module.start(context(i)).whenComplete((result, error) -> onMain(() -> {
            if (error != null || result == null || !result.success()) {
                fail(i.id(), new EventFailure("RECOVERY_FAILED", error == null ? (result == null ? "null result" : result.message()) : rootMessage(error), false));
                return;
            }
            if (usesAdmissionQueue(i)) {
                admissions.open(i);
                fillCapacity(i);
                if (!i.definition().join().lateJoin() || !clock.instant().isBefore(i.lateJoinCloseAt())) admissions.close(i);
            } else {
                autoJoinKnownParticipants(i);
            }
        }));
    }

    private boolean withinModuleStartupGrace(UUID instanceId) {
        Instant first = moduleWaitStartedAt.computeIfAbsent(instanceId, ignored -> clock.instant());
        return clock.instant().isBefore(first.plus(MODULE_STARTUP_GRACE));
    }

    private void clearModuleWait(UUID instanceId) { moduleWaitStartedAt.remove(instanceId); }

    private void handleTransition(ScheduledTransition t) {
        EventInstance i = instances.get(t.instanceId());
        if (i == null || i.state().terminal()) return; // stale transition
        switch (t.type()) {
            case BOSSBAR_SHOW -> countdownBossBars.show(i);
            case REGISTRATION_OPEN -> {
                if (i.state() == EventState.SCHEDULED) setState(i, EventState.REGISTRATION_OPEN, "");
            }
            case PREPARE -> prepare(i);
            case LOBBY -> openLobby(i, t);
            case START -> start(i, t);
            case LATE_JOIN_CLOSE -> {
                if (admissions.isOpen(i)) admissions.close(i);
            }
            case END -> stop(i, EventStopReason.SCHEDULED_END);
        }
    }

    private void prepare(EventInstance i) {
        if (i.prepared() || i.state().ordinal() > EventState.PREPARING.ordinal()) return;
        HexEventModule module = modules.find(i.definition().moduleId()).orElse(null);
        if (module == null) {
            if (withinModuleStartupGrace(i.id())) Bukkit.getScheduler().runTaskLater(plugin, () -> prepare(i), 20L);
            else fail(i.id(), new EventFailure("MODULE_UNAVAILABLE", "Brak modułu " + i.definition().moduleId(), true));
            return;
        }
        clearModuleWait(i.id());
        Optional<String> runtimeConflict = runtimeConflict(i, module);
        if (runtimeConflict.isPresent()) {
            fail(i.id(), new EventFailure("EXCLUSIVE_GROUP_BUSY", runtimeConflict.get(), true));
            return;
        }
        setState(i, EventState.PREPARING, "");
        module.prepare(context(i)).whenComplete((result, error) -> onMain(() -> {
            if (error != null || result == null || !result.success()) {
                fail(i.id(), new EventFailure("PREPARE_FAILED", error == null ? (result == null ? "null result" : result.message()) : rootMessage(error), false));
                return;
            }
            i.prepared(true);
            persistRuntimeFireAndForget(i, "runtime:" + i.id());
        }));
    }

    private void openLobby(EventInstance i, ScheduledTransition transition) {
        countdownBossBars.hide(i.id());
        if (!i.definition().lobby().enabled() || i.state().ordinal() >= EventState.LOBBY.ordinal()) return;
        if (!i.prepared()) { retry(transition); return; }
        HexEventModule module = modules.find(i.definition().moduleId()).orElse(null);
        if (module == null) {
            if (withinModuleStartupGrace(i.id())) Bukkit.getScheduler().runTaskLater(plugin, () -> openLobby(i, transition), 20L);
            else fail(i.id(), new EventFailure("MODULE_UNAVAILABLE", "Brak modułu " + i.definition().moduleId(), true));
            return;
        }
        clearModuleWait(i.id());
        if (!module.capabilities().supportsLobby()) {
            fail(i.id(), new EventFailure("LOBBY_UNSUPPORTED", "Moduł nie obsługuje lobby", false));
            return;
        }
        setState(i, EventState.LOBBY, "");
        if (usesAdmissionQueue(i)) {
            admissions.open(i);
            fillCapacity(i);
        } else if (i.definition().join().autoJoinRegistered()) {
            autoJoinRegisteredDirect(i);
        }
    }

    private void start(EventInstance i, ScheduledTransition transition) {
        if (!i.definition().lobby().enabled()) countdownBossBars.hide(i.id());
        if (i.state() == EventState.RUNNING || i.state().ordinal() > EventState.RUNNING.ordinal()) return;
        if (!i.prepared()) { retry(transition); return; }

        if (usesAdmissionQueue(i) && !admissions.isOpen(i)) admissions.open(i);
        int min = i.definition().capacity().minPlayers();
        int ready = readyPlayerCount(i);
        if (ready < min && i.definition().capacity().onTooFew() != EventDefinition.TooFewPolicy.START_ANYWAY) {
            if (i.definition().capacity().onTooFew() == EventDefinition.TooFewPolicy.CANCEL_AND_REFUND) registrations.refundAllAsync(i).exceptionally(error -> { plugin.getLogger().log(Level.SEVERE, "Błąd refundu po zbyt małej liczbie graczy " + i.id(), error); return null; });
            admissions.clear(i);
            finishWithoutModule(i, EventState.CANCELLED, "TOO_FEW_PLAYERS");
            return;
        }

        HexEventModule module = modules.find(i.definition().moduleId()).orElse(null);
        if (module == null) {
            if (withinModuleStartupGrace(i.id())) Bukkit.getScheduler().runTaskLater(plugin, () -> start(i, transition), 20L);
            else fail(i.id(), new EventFailure("MODULE_UNAVAILABLE", "Brak modułu " + i.definition().moduleId(), true));
            return;
        }
        clearModuleWait(i.id());
        module.start(context(i)).whenComplete((result, error) -> onMain(() -> {
            if (error != null || result == null || !result.success()) {
                fail(i.id(), new EventFailure("START_FAILED", error == null ? (result == null ? "null result" : result.message()) : rootMessage(error), false));
                return;
            }
            setState(i, EventState.RUNNING, "");
            if (usesAdmissionQueue(i)) {
                fillCapacity(i);
                if (!i.definition().join().lateJoin()) admissions.close(i);
            } else if (i.definition().join().autoJoinRegistered()) {
                autoJoinRegisteredDirect(i);
            }
        }));
    }

    private int readyPlayerCount(EventInstance i) {
        if (i.definition().lobby().enabled()) return i.participants().size();
        if (usesAdmissionQueue(i)) return admissions.queueSize(i) + i.participants().size();
        if (i.definition().registration().enabled()) {
            int count = 0;
            for (UUID playerId : i.registeredPlayers()) {
                Player p = Bukkit.getPlayer(playerId);
                if (p != null && p.isOnline() && registrations.checkEligibility(p, i).success()) count++;
            }
            return count;
        }
        return i.participants().size();
    }

    public void stop(EventInstance i, EventStopReason reason) {
        countdownBossBars.hide(i.id());
        if (completionInFlight.contains(i.id()) && reason != EventStopReason.MODULE_FAILURE) return;
        if (i.state().terminal() || i.state() == EventState.FINISHING) return;
        if (admissions.isOpen(i)) admissions.close(i);
        HexEventModule module = modules.find(i.definition().moduleId()).orElse(null);
        if (module == null) {
            fail(i.id(), new EventFailure("MODULE_UNAVAILABLE_DURING_STOP", "Moduł zniknął podczas kończenia eventu.", true));
            return;
        }
        setState(i, EventState.FINISHING, "");
        module.stop(i.id(), reason).whenComplete((result, error) -> onMain(() -> {
            if (error != null || result == null || !result.success()) {
                fail(i.id(), new EventFailure("STOP_FAILED", error == null ? (result == null ? "null result" : result.message()) : rootMessage(error), false));
                return;
            }
            finishWithoutModule(i, EventState.FINISHED, "");
        }));
    }

    public EventJoinResult requestJoin(Player player, UUID instanceId, JoinSource source) {
        EventInstance i = instances.get(instanceId);
        if (i == null) return EventJoinResult.denied("Nie znaleziono eventu.");
        EventDefinition def = i.definition();
        UUID playerId = player.getUniqueId();
        if (i.participants().contains(playerId)) return EventJoinResult.alreadyJoined();
        if (i.state() != EventState.LOBBY && i.state() != EventState.RUNNING) return new EventJoinResult(EventJoinResult.Status.NOT_RUNNING, "Event nie przyjmuje teraz graczy.");
        if (admissions.blocksRejoin(i, playerId)) return EventJoinResult.denied("Nie możesz już wrócić do tego wydarzenia.");

        boolean manualSource = source == JoinSource.GUI || source == JoinSource.WORLD_PORTAL || source == JoinSource.NPC || source == JoinSource.COMMAND;
        if (manualSource && !def.join().manualEntry()) return EventJoinResult.denied("Ten event nie pozwala na ręczne dołączanie.");
        if (i.state() == EventState.RUNNING && (!def.join().lateJoin() || !clock.instant().isBefore(i.lateJoinCloseAt()))) {
            return EventJoinResult.denied("Dołączanie w trakcie jest już zamknięte.");
        }
        if (def.registration().required() && !i.registeredPlayers().contains(playerId)) return EventJoinResult.denied("Musisz być zapisany na wydarzenie.");
        if (!i.registeredPlayers().contains(playerId) && def.join().lateJoinScope() == EventDefinition.LateJoinScope.REGISTERED_ONLY) return EventJoinResult.denied("Dołączać mogą tylko zapisani gracze.");
        if (!i.registeredPlayers().contains(playerId) && !def.costs().isEmpty()) return EventJoinResult.denied("Ten event ma koszt wejścia — wymagany jest wcześniejszy zapis.");

        if (usesAdmissionQueue(i) && i.registeredPlayers().contains(playerId)) {
            if (!admissions.isOpen(i)) return EventJoinResult.denied("Kolejka do wydarzenia jest już zamknięta.");
            admissions.onPlayerAvailable(i, player);
            fillCapacity(i);
            if (i.participants().contains(playerId)) return EventJoinResult.joined();
            if (admissions.queued(i, playerId)) {
                int pos = admissions.position(i, playerId);
                return new EventJoinResult(EventJoinResult.Status.FULL,
                        "Jesteś " + pos + ". w kolejce. Jeśli zwolni się miejsce, zostaniesz automatycznie dołączony.");
            }
            return EventJoinResult.denied("Nie możesz zostać dopuszczony do wydarzenia.");
        }

        // Unregistered late-join must never jump ahead of an existing registered queue.
        if (usesAdmissionQueue(i) && admissions.queueSize(i) > 0) {
            return new EventJoinResult(EventJoinResult.Status.FULL, "Pierwszeństwo ma aktywna kolejka zapisanych graczy.");
        }

        RegistrationResult eligibility = registrations.checkEligibility(player, i);
        if (!eligibility.success()) return EventJoinResult.denied(eligibility.message());
        int max = def.capacity().maxPlayers();
        if (max > 0 && i.participants().size() >= max) return new EventJoinResult(EventJoinResult.Status.FULL, "Brak wolnych miejsc.");
        return directModuleJoin(player, i, source);
    }

    private EventJoinResult directModuleJoin(Player player, EventInstance i, JoinSource source) {
        HexEventModule module = modules.find(i.definition().moduleId()).orElse(null);
        if (module == null) return new EventJoinResult(EventJoinResult.Status.MODULE_UNAVAILABLE, "Moduł eventu jest niedostępny.");
        EventJoinResult result;
        try {
            result = module.join(new EventJoinRequest(i.id(), player.getUniqueId(), player.getName(), source, context(i)));
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.SEVERE, "Błąd JOIN " + i.id(), throwable);
            return EventJoinResult.error(rootMessage(throwable));
        }
        if (result != null && result.success()) {
            i.participants().add(player.getUniqueId());
            UUID joinedInstanceId = i.id();
            UUID joinedPlayerId = player.getUniqueId();
            persistence.fireAndForget(playerLane(joinedInstanceId, joinedPlayerId), "participant-join:" + joinedInstanceId + ":" + joinedPlayerId, () -> registrationRepository.markJoined(joinedInstanceId, joinedPlayerId));
        }
        return result == null ? EventJoinResult.error("Moduł zwrócił null") : result;
    }

    /** Fill free slots strictly from one ordered queue snapshot. Already admitted players are never displaced. */
    private void fillCapacity(EventInstance i) {
        if (!usesAdmissionQueue(i) || !admissions.isOpen(i)) return;
        int max = i.definition().capacity().maxPlayers();
        List<AdmissionEntry> candidates = admissions.onlineCandidates(i);
        for (AdmissionEntry candidate : candidates) {
            if (max > 0 && i.participants().size() >= max) break;
            Player player = Bukkit.getPlayer(candidate.playerId());
            if (player == null || !player.isOnline()) continue;
            RegistrationResult eligibility = registrations.checkEligibility(player, i);
            if (!eligibility.success()) {
                admissions.reject(i, player.getUniqueId(), "ELIGIBILITY_FAILED_AT_JOIN");
                player.sendMessage(color("&cNie spełniasz już wymagań wydarzenia: " + eligibility.message()));
                continue;
            }

            admissions.markAdmitted(i, player.getUniqueId());
            EventJoinResult result = directModuleJoin(player, i, JoinSource.AUTO_START);
            if (result.success()) {
                admissions.markParticipating(i, player.getUniqueId());
                player.sendMessage(color("&aZwolniło się miejsce — zostałeś automatycznie dołączony do wydarzenia &f" + i.definition().displayName() + "&a."));
                continue;
            }
            admissions.requeueAfterJoinFailure(i, player.getUniqueId(), "JOIN_FAILED:" + result.status());
            plugin.getLogger().warning("Nie udało się automatycznie dołączyć gracza " + player.getName() + " do " + i.id() + ": " + result.status() + " / " + result.message());
            break; // avoid hammering a broken module in one tick
        }
        admissions.notifyQueuePositions(i, false);
    }

    public void leave(Player player, UUID instanceId, LeaveReason reason) {
        EventInstance i = instances.get(instanceId);
        if (i == null) return;
        UUID playerId = player.getUniqueId();
        boolean wasParticipant = i.participants().contains(playerId);
        if (wasParticipant) {
            modules.find(i.definition().moduleId()).ifPresent(m -> m.leave(instanceId, playerId, reason));
            i.participants().remove(playerId);
            boolean forfeited = reason == LeaveReason.PLAYER_REQUEST || reason == LeaveReason.DISCONNECT || reason == LeaveReason.KICK;
            String participantLeftStatus = forfeited ? "LEFT_FORFEITED" : "LEFT";
            persistence.fireAndForget(playerLane(instanceId, playerId), "participant-left:" + instanceId + ":" + playerId, () -> registrationRepository.markLeft(instanceId, playerId, participantLeftStatus));
            if (forfeited && i.registeredPlayers().contains(playerId)) {
                admissions.forfeit(i, playerId, "LEFT_AFTER_ADMISSION:" + reason.name());
            }
            if (forfeited) player.sendMessage(color("&cOpuściłeś wydarzenie. Tracisz miejsce, prawo powrotu i wpłaconą opłatę."));
            if (admissions.isOpen(i)) fillCapacity(i);
            return;
        }

        // Leaving an active queue after admission has started is also a forfeit, not a cancellation/refund.
        if (admissions.queued(i, playerId) && (reason == LeaveReason.PLAYER_REQUEST || reason == LeaveReason.KICK)) {
            admissions.forfeit(i, playerId, "LEFT_QUEUE_AFTER_ADMISSION:" + reason.name());
            player.sendMessage(color("&cOpuściłeś kolejkę po rozpoczęciu admission. Wpłacona opłata przepada."));
            admissions.notifyQueuePositions(i, false);
        }
    }

    public void onPlayerJoin(Player player) {
        rewardService.deliverForPlayer(player.getUniqueId()).exceptionally(error -> { plugin.getLogger().warning("Nie udało się dostarczyć oczekujących rewardów dla " + player.getName() + ": " + rootMessage(error)); return null; });
        registrations.retryPendingRefundsAsync(player).whenComplete((finalizedRefunds, error) -> onMain(() -> {
            if (error != null) {
                plugin.getLogger().warning("Nie udało się ponowić oczekujących refundów dla " + player.getName() + ": " + rootMessage(error));
                return;
            }
            for (UUID instanceId : finalizedRefunds) instance(instanceId).ifPresent(i -> admissions.markRefundFinalized(i, player.getUniqueId()));
        }));

        for (EventInstance i : List.copyOf(instances.values())) {
            if (i.state() != EventState.LOBBY && i.state() != EventState.RUNNING) continue;
            if (admissions.blocksRejoin(i, player.getUniqueId())) continue;

            if (usesAdmissionQueue(i) && admissions.isOpen(i) && i.registeredPlayers().contains(player.getUniqueId())) {
                admissions.onPlayerAvailable(i, player);
                fillCapacity(i);
                if (admissions.queued(i, player.getUniqueId())) admissions.notifyQueuePositions(i, true);
                continue;
            }

            if (i.state() != EventState.RUNNING || !i.definition().join().lateJoin() || !clock.instant().isBefore(i.lateJoinCloseAt())) continue;
            boolean registered = i.registeredPlayers().contains(player.getUniqueId());
            boolean scopeOk = i.definition().join().lateJoinScope() == EventDefinition.LateJoinScope.ELIGIBLE_PLAYERS || registered;
            if (!scopeOk || (!registered && !i.definition().costs().isEmpty())) continue;
            if (!registrations.checkEligibility(player, i).success()) continue;
            if (registered && i.definition().join().autoJoinRegistered()) {
                requestJoin(player, i.id(), JoinSource.AUTO_START);
            } else {
                player.sendMessage(color("&e" + i.definition().displayName() + " już trwa! &7Dołącz: &f/event join " + i.id()));
            }
        }
    }

    private void autoJoinRegisteredDirect(EventInstance i) {
        for (UUID playerId : List.copyOf(i.registeredPlayers())) {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) requestJoin(p, i.id(), JoinSource.AUTO_START);
        }
    }

    private void autoJoinKnownParticipants(EventInstance i) {
        for (UUID playerId : List.copyOf(i.participants())) {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) directModuleJoin(p, i, JoinSource.AUTO_START);
        }
    }

    public boolean complete(UUID instanceId, EventResult result) {
        EventInstance i = instances.get(instanceId);
        if (i == null || i.state().terminal() || i.state() == EventState.FINISHING || completionInFlight.contains(instanceId)) return false;
        if (result == null) return fail(instanceId, new EventFailure("NULL_RESULT", "Moduł zakończył event bez EventResult.", false));
        completionInFlight.add(instanceId);
        if (admissions.isOpen(i)) admissions.close(i);
        rewardService.planCompleted(i, result).whenComplete((ignored, error) -> onMain(() -> {
            if (error != null) {
                completionInFlight.remove(instanceId);
                fail(instanceId, new EventFailure("RESULT_REWARD_PERSISTENCE_FAILED", rootMessage(error), true));
                return;
            }
            setState(i, EventState.FINISHING, "");
            persistence.barrier(instanceLane(i.id())).whenComplete((persisted,persistError) -> onMain(() -> {
                if (persistError != null) {
                    completionInFlight.remove(instanceId);
                    fail(instanceId, new EventFailure("FINISHING_PERSISTENCE_FAILED", rootMessage(persistError), true));
                    return;
                }
                finishCompleted(i);
            }));
        }));
        return true;
    }

    private void finishCompleted(EventInstance i) {
        HexEventModule module = modules.find(i.definition().moduleId()).orElse(null);
        if (module == null) {
            completionInFlight.remove(i.id());
            fail(i.id(), new EventFailure("MODULE_UNAVAILABLE_DURING_COMPLETION", "Moduł zniknął podczas finalizacji", true));
            return;
        }
        module.stop(i.id(), EventStopReason.COMPLETED).whenComplete((result,error) -> onMain(() -> {
            completionInFlight.remove(i.id());
            if (error != null || result == null || !result.success()) {
                fail(i.id(), new EventFailure("STOP_AFTER_COMPLETION_FAILED", error == null ? (result == null ? "null result" : result.message()) : rootMessage(error), false));
                return;
            }
            finishWithoutModule(i, EventState.FINISHED, "");
            persistence.barrier(instanceLane(i.id())).whenComplete((v,e) -> {
                if (e != null) plugin.getLogger().warning("Nie udało się utrwalić FINISHED przed reward delivery " + i.id() + ": " + rootMessage(e));
                else rewardService.deliverForInstance(i.id());
            });
        }));
    }

    public boolean fail(UUID instanceId, EventFailure failure) {
        countdownBossBars.hide(instanceId);
        EventInstance i = instances.get(instanceId);
        if (i == null || i.state().terminal()) return false;
        EventState previous = i.state();
        completionInFlight.remove(instanceId);
        clearModuleWait(i.id());
        i.lastError(failure.code() + ": " + failure.message());
        rewardService.cancelPending(i.id(), "EVENT_FAILED:" + failure.code()).exceptionally(error -> { plugin.getLogger().warning("Nie udało się anulować pending rewards dla " + i.id() + ": " + rootMessage(error)); return null; });
        admissions.clear(i);
        if (i.definition().registration().enabled() && !i.registeredPlayers().isEmpty()) {
            registrations.refundAllAsync(i).exceptionally(refundError -> { plugin.getLogger().log(Level.SEVERE, "Błąd refundu po awarii eventu " + i.id(), refundError); return null; });
        }
        setState(i, EventState.FAILED, i.lastError());
        if (previous != EventState.FINISHING) {
            modules.find(i.definition().moduleId()).ifPresent(module -> {
                try { module.stop(i.id(), EventStopReason.MODULE_FAILURE); }
                catch (Throwable cleanupError) { plugin.getLogger().log(Level.WARNING, "Błąd cleanup modułu po FAILED " + i.id(), cleanupError); }
            });
        }
        return true;
    }

    private Optional<String> runtimeConflict(EventInstance candidate, HexEventModule module) {
        Set<String> candidateGroups = new HashSet<>(candidate.definition().exclusiveGroups());
        boolean moduleSingleInstance = !module.capabilities().supportsMultipleConcurrentInstances();
        for (EventInstance other : instances.values()) {
            if (other.id().equals(candidate.id()) || other.state().terminal()) continue;
            if (!occupiesRuntimeResource(other.state())) continue;
            if (moduleSingleInstance && other.definition().moduleId().equals(candidate.definition().moduleId())) {
                return Optional.of("Moduł " + candidate.definition().moduleId() + " nie obsługuje równoległych instancji; aktywna jest " + other.id());
            }
            if (!candidateGroups.isEmpty()) {
                for (String group : other.definition().exclusiveGroups()) {
                    if (candidateGroups.contains(group)) {
                        return Optional.of("Exclusive group '" + group + "' jest zajęta przez " + other.definition().id() + " / " + other.id());
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean occupiesRuntimeResource(EventState state) {
        return state == EventState.PREPARING || state == EventState.LOBBY || state == EventState.RUNNING || state == EventState.FINISHING;
    }

    private boolean usesAdmissionQueue(EventInstance i) {
        return i.definition().registration().enabled() && i.definition().capacity().maxPlayers() > 0;
    }

    private void finishWithoutModule(EventInstance i, EventState state, String message) {
        countdownBossBars.hide(i.id());
        clearModuleWait(i.id());
        i.lastError(message);
        setState(i, state, message);
    }

    private CompletableFuture<Void> setState(EventInstance i, EventState state, String error) {
        i.state(state);
        if (error != null && !error.isBlank()) i.lastError(error);
        EventInstanceRepository.RuntimeSnapshot snapshot = EventInstanceRepository.runtimeSnapshot(i);
        CompletableFuture<Void> persisted = persistence.write(instanceLane(i.id()), () -> instanceRepository.updateRuntime(snapshot));
        if (state.terminal()) {
            refreshPublishedScheduleAsync().whenComplete((ignored, publishError) -> {
                if (publishError != null) plugin.getLogger().log(Level.SEVERE, "Nie udało się odświeżyć 7-dniowego harmonogramu po zakończeniu eventu " + i.id(), publishError);
            });
        }
        return persisted;
    }

    private void persistRuntimeFireAndForget(EventInstance i, String operation) {
        EventInstanceRepository.RuntimeSnapshot snapshot = EventInstanceRepository.runtimeSnapshot(i);
        persistence.fireAndForget(instanceLane(i.id()), operation, () -> instanceRepository.updateRuntime(snapshot));
    }

    private static String instanceLane(UUID instanceId) { return "instance:" + instanceId; }
    private static String playerLane(UUID instanceId, UUID playerId) { return "player:" + instanceId + ":" + playerId; }

    private void retry(ScheduledTransition t) {
        EventInstance i = instances.get(t.instanceId());
        if (i == null || i.state().terminal()) return;
        if (clock.instant().isBefore(i.endAt())) Bukkit.getScheduler().runTaskLater(plugin, () -> handleTransition(t), 20L);
        else fail(t.instanceId(), new EventFailure("TRANSITION_TIMEOUT", "Nie ukończono przygotowania przed końcem eventu", false));
    }

    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    private EventExecutionContext context(EventInstance i) {
        return new EventExecutionContext(i.id(), i.definition().id(), i.definition().displayName(), i.lobbyAt(), i.startAt(), i.endAt(), i.definition().moduleSettings(), i.registeredPlayers());
    }

    public Optional<EventInstance> instance(UUID id) { return Optional.ofNullable(instances.get(id)); }
    public Collection<EventInstance> allInstances() { return List.copyOf(instances.values()); }

    public EventAvailability availability(EventInstance i) {
        HexEventModule module = modules.find(i.definition().moduleId()).orElse(null);
        if (module == null) return EventAvailability.MODULE_UNAVAILABLE;
        EventAvailability moduleAvailability;
        try { moduleAvailability = module.availability(i.definition().moduleSettings()); }
        catch (Throwable error) { return EventAvailability.DEPENDENCY_UNAVAILABLE; }
        if (moduleAvailability != EventAvailability.AVAILABLE) return moduleAvailability;
        EventModuleCapabilities caps = module.capabilities();
        if (i.definition().lobby().enabled() && !caps.supportsLobby()) return EventAvailability.MISCONFIGURED;
        if (i.definition().join().lateJoin() && !caps.supportsLateJoin()) return EventAvailability.MISCONFIGURED;
        for (EventDefinition.RequirementSpec spec : i.definition().requirements()) {
            var p = requirementProviders.find(spec.type()).orElse(null);
            if (p == null || !p.available()) return EventAvailability.DEPENDENCY_UNAVAILABLE;
        }
        for (EventDefinition.CostSpec spec : i.definition().costs()) {
            var p = costProviders.find(spec.type()).orElse(null);
            if (p == null || !p.available()) return EventAvailability.DEPENDENCY_UNAVAILABLE;
        }
        for (EventDefinition.RewardRule rule : i.definition().rewards()) {
            for (EventDefinition.RewardGrantSpec grant : rule.grants()) {
                var p = rewardProviders.find(grant.type()).orElse(null);
                if (p == null || !p.available()) return EventAvailability.DEPENDENCY_UNAVAILABLE;
            }
        }
        return EventAvailability.AVAILABLE;
    }

    public Optional<EventInstance> nextEvent() {
        return allInstances().stream().filter(i -> !i.state().terminal() && i.endAt().isAfter(clock.instant())).min(Comparator.comparing(EventInstance::startAt));
    }

    public Optional<EventInstance> nextEvent(String eventId) {
        return allInstances().stream().filter(i -> i.definition().id().equalsIgnoreCase(eventId) && !i.state().terminal() && i.endAt().isAfter(clock.instant())).min(Comparator.comparing(EventInstance::startAt));
    }

    public Optional<EventInstance> activeEvent(String eventId) {
        return allInstances().stream().filter(i -> i.definition().id().equalsIgnoreCase(eventId) && (i.state() == EventState.LOBBY || i.state() == EventState.RUNNING)).findFirst();
    }

    public Optional<EventInstance> playerRelevantEvent(UUID playerId) {
        Optional<EventInstance> active = allInstances().stream()
                .filter(i -> (i.state() == EventState.LOBBY || i.state() == EventState.RUNNING) &&
                        (i.registeredPlayers().contains(playerId) || admissions.status(i, playerId) != null || i.participants().contains(playerId)))
                .min(Comparator.comparing(EventInstance::startAt));
        if (active.isPresent()) return active;
        return allInstances().stream()
                .filter(i -> !i.state().terminal() && i.endAt().isAfter(clock.instant()) && i.registeredPlayers().contains(playerId))
                .min(Comparator.comparing(EventInstance::startAt));
    }

    public List<EventInstance> upcomingDays(int days, ZoneId zone) {
        LocalDate from = LocalDate.now(zone), to = from.plusDays(Math.max(0, days - 1));
        return allInstances().stream().filter(i -> {
            LocalDate d = i.occurrenceAt().atZone(zone).toLocalDate();
            return !d.isBefore(from) && !d.isAfter(to) && !i.state().terminal();
        }).sorted(Comparator.comparing(EventInstance::occurrenceAt)).toList();
    }

    public AdmissionStatus admissionStatus(EventInstance i, UUID playerId) { return admissions.status(i, playerId); }
    public int queuePosition(EventInstance i, UUID playerId) { return admissions.position(i, playerId); }
    public int registrationPosition(EventInstance i, UUID playerId) { return admissions.registrationPosition(i, playerId); }
    public int queueSize(EventInstance i) { return admissions.queueSize(i); }
    public String queuePriority(EventInstance i, UUID playerId) { return admissions.priorityName(i, playerId); }
    public boolean admissionOpen(EventInstance i) { return admissions.isOpen(i); }

    public List<String> validate() {
        List<String> out = new ArrayList<>();
        for (EventDefinition d : eventsConfig.definitions().values()) {
            EventInstance probe = compiler.compile(d, clock.instant(), 7).stream().findFirst().orElse(null);
            EventAvailability a = probe == null ? EventAvailability.MISCONFIGURED : availability(probe);
            String detail = "";
            HexEventModule module = modules.find(d.moduleId()).orElse(null);
            if (module == null) detail = " (module " + d.moduleId() + ")";
            else if (a != EventAvailability.AVAILABLE) {
                try { detail = " (" + module.availabilityReason(d.moduleSettings()) + ")"; } catch (Throwable ignored) { }
            }
            out.add("[" + (a == EventAvailability.AVAILABLE ? "OK" : "ERROR") + "] " + d.id() + " -> " + a + detail);
        }
        return out;
    }

    public EngineConfig engineConfig() { return engineConfig; }
    public EventsConfig eventsConfig() { return eventsConfig; }
    public Instant compiledUntil() { return compiledUntil; }
    public int queuedTransitions() { return scheduler.queuedTransitions(); }

    private <T> CompletableFuture<T> onMainFuture(Supplier<T> work) {
        if (Bukkit.isPrimaryThread()) {
            try { return CompletableFuture.completedFuture(work.get()); }
            catch (Throwable error) { CompletableFuture<T> failed = new CompletableFuture<>(); failed.completeExceptionally(error); return failed; }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { future.complete(work.get()); }
            catch (Throwable error) { future.completeExceptionally(error); }
        });
        return future;
    }

    private record RestoreData(List<EventInstanceRepository.StoredInstance> instances,
                               List<RegistrationRepository.RegistrationRow> registrations,
                               List<RegistrationRepository.ParticipantRow> participants,
                               List<AdmissionRepository.AdmissionRow> admissions) { }

    private record PublishedScheduleSnapshot(List<EventSchedule7dRepository.ScheduleRow> rows,
                                             LocalDate windowDate,
                                             ZoneId zone,
                                             Instant publishedAt) { }

    private static String color(String text) { return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text); }
    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
