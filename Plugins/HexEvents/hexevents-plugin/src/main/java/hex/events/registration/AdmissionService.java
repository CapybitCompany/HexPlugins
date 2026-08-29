package hex.events.registration;

import hex.events.model.EventInstance;
import hex.events.persistence.AdmissionRepository;
import hex.events.persistence.PersistenceExecutor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Owns the in-memory admission queues. It never executes module JOIN by itself;
 * EventLifecycleService decides when capacity is available and performs the actual join.
 */
public final class AdmissionService {
    private static final long POSITION_MESSAGE_THROTTLE_MS = 5_000L;

    private final Plugin plugin;
    private final Clock clock;
    private final AdmissionRepository repository;
    private final RegistrationService registrations;
    private final PersistenceExecutor persistence;
    private final EventQueuePriorityResolver priorityResolver = new EventQueuePriorityResolver();
    private final Map<UUID, AdmissionQueue> queues = new HashMap<>();
    private final Map<UUID, Map<UUID, AdmissionEntry>> states = new HashMap<>();
    private final Set<UUID> openInstances = new HashSet<>();
    private final Map<String, NotificationState> notifications = new HashMap<>();

    public AdmissionService(Plugin plugin, Clock clock, AdmissionRepository repository, RegistrationService registrations, PersistenceExecutor persistence) {
        this.plugin = plugin;
        this.clock = clock;
        this.repository = repository;
        this.registrations = registrations;
        this.persistence = persistence;
    }

    public void restore(Collection<EventInstance> instances, List<AdmissionRepository.AdmissionRow> rows) {
        Map<UUID, EventInstance> byId = new HashMap<>();
        for (EventInstance instance : instances) byId.put(instance.id(), instance);
        for (AdmissionRepository.AdmissionRow row : rows) {
            EventInstance instance = byId.get(row.instanceId());
            if (instance == null) continue;
            states.computeIfAbsent(instance.id(), ignored -> new HashMap<>()).put(row.entry().playerId(), row.entry());
            if (row.entry().status() == AdmissionStatus.QUEUED) {
                queues.computeIfAbsent(instance.id(), ignored -> new AdmissionQueue()).add(row.entry());
            }
            if (row.entry().status() == AdmissionStatus.QUEUED || row.entry().status() == AdmissionStatus.WAITING_FOR_START ||
                    row.entry().status() == AdmissionStatus.ADMITTED || row.entry().status() == AdmissionStatus.PARTICIPATING) {
                if (instance.state() == hex.events.api.EventState.LOBBY || instance.state() == hex.events.api.EventState.RUNNING) {
                    openInstances.add(instance.id());
                }
            }
        }
    }

    public void open(EventInstance instance) {
        openInstances.add(instance.id());
        states.computeIfAbsent(instance.id(), ignored -> new HashMap<>());
        queues.computeIfAbsent(instance.id(), ignored -> new AdmissionQueue());

        for (UUID playerId : List.copyOf(instance.registeredPlayers())) {
            AdmissionEntry current = state(instance, playerId).orElse(null);
            if (current != null && current.status().blocksRejoin()) continue;
            if (current != null && (current.status() == AdmissionStatus.PARTICIPATING || current.status() == AdmissionStatus.ADMITTED || current.status() == AdmissionStatus.QUEUED)) continue;

            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                queueOnline(instance, player, false);
            } else {
                remember(instance, new AdmissionEntry(playerId, instance.registrationName(playerId), instance.registrationTime(playerId),
                        current == null ? EventQueuePriority.NORMAL : current.priority(), AdmissionStatus.WAITING_FOR_START,
                        "OFFLINE_AT_ADMISSION_OPEN", System.currentTimeMillis()));
            }
        }
        notifyQueuePositions(instance, true);
    }

    public void close(EventInstance instance) {
        if (!openInstances.remove(instance.id())) return;
        AdmissionQueue queue = queues.get(instance.id());
        Set<UUID> processed = new HashSet<>();

        if (queue != null) {
            for (AdmissionEntry entry : queue.ordered()) {
                processed.add(entry.playerId());
                Player online = Bukkit.getPlayer(entry.playerId());
                if (online != null && online.isOnline()) {
                    startCapacityRefund(instance, online, entry.playerId());
                } else {
                    registrations.forfeit(instance, entry.playerId(), "NO_SHOW");
                    setStatus(instance, entry.playerId(), AdmissionStatus.NO_SHOW, "NO_SHOW_AT_ADMISSION_CLOSE");
                }
                queue.remove(entry.playerId());
            }
        }

        // Registrations that never made it into the queue are finalized by the same explicit policy.
        for (UUID playerId : List.copyOf(instance.registeredPlayers())) {
            if (processed.contains(playerId)) continue;
            AdmissionEntry entry = state(instance, playerId).orElse(null);
            AdmissionStatus status = entry == null ? AdmissionStatus.REGISTERED : entry.status();
            Player online = Bukkit.getPlayer(playerId);
            boolean isOnline = online != null && online.isOnline();
            AdmissionClosePolicy.Decision decision = AdmissionClosePolicy.decide(status, isOnline);
            if (decision == AdmissionClosePolicy.Decision.NO_SHOW) {
                registrations.forfeit(instance, playerId, "NO_SHOW");
                setStatus(instance, playerId, AdmissionStatus.NO_SHOW, "NO_SHOW_AT_ADMISSION_CLOSE");
            } else if (decision == AdmissionClosePolicy.Decision.REFUND_CAPACITY && online != null) {
                startCapacityRefund(instance, online, playerId);
            }
        }
    }

    private void startCapacityRefund(EventInstance instance, Player online, UUID playerId) {
        setStatus(instance, playerId, AdmissionStatus.QUEUE_REFUND_PENDING, "QUEUE_REFUND_IN_PROGRESS");
        registrations.refundForCapacityAsync(online, instance).whenComplete((outcome, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        setStatus(instance, playerId, AdmissionStatus.QUEUE_REFUND_PENDING, "QUEUE_REFUND_RECONCILIATION_REQUIRED");
                        online.sendMessage(color("&eNie udało Ci się dołączyć do wydarzenia. Zwrot wymaga bezpiecznej rekoncyliacji — skontaktuj się z administracją."));
                        return;
                    }
                    AdmissionStatus next = outcome == RefundOutcome.COMPLETE ? AdmissionStatus.QUEUE_REFUNDED : AdmissionStatus.QUEUE_REFUND_PENDING;
                    String reason = outcome == RefundOutcome.COMPLETE ? "QUEUE_REFUNDED_CAPACITY" :
                            (outcome == RefundOutcome.PENDING ? "QUEUE_REFUND_PENDING" : "QUEUE_REFUND_RECONCILIATION_REQUIRED");
                    setStatus(instance, playerId, next, reason);
                    if (outcome == RefundOutcome.COMPLETE) {
                        online.sendMessage(color("&cNie udało Ci się dołączyć do wydarzenia &f" + instance.definition().displayName() +
                                "&c — wszystkie pobrane opłaty zostały zwrócone."));
                    } else if (outcome == RefundOutcome.PENDING) {
                        online.sendMessage(color("&eNie udało Ci się dołączyć do wydarzenia. Zwrot opłaty oczekuje na bezpieczne dostarczenie."));
                    } else {
                        online.sendMessage(color("&eNie udało Ci się dołączyć do wydarzenia. Zwrot wymaga bezpiecznej rekoncyliacji — skontaktuj się z administracją."));
                    }
                }));
    }

    /** System cancellation/failure path. Capacity/no-show rules do not apply. */
    public void clear(EventInstance instance) {
        openInstances.remove(instance.id());
        AdmissionQueue queue = queues.remove(instance.id());
        if (queue != null) queue.clear();
    }

    public boolean isOpen(EventInstance instance) { return openInstances.contains(instance.id()); }

    public void markRefundFinalized(EventInstance instance, UUID playerId) {
        AdmissionEntry current = state(instance, playerId).orElse(null);
        if (current != null && current.status() == AdmissionStatus.QUEUE_REFUND_PENDING) {
            setStatus(instance, playerId, AdmissionStatus.QUEUE_REFUNDED, "QUEUE_REFUNDED_CAPACITY");
        }
    }

    public void onRegistered(EventInstance instance, Player player, EventQueuePriority snapshottedPriority) {
        rememberRuntimeOnly(instance, new AdmissionEntry(player.getUniqueId(), player.getName(), instance.registrationTime(player.getUniqueId()),
                snapshottedPriority, AdmissionStatus.REGISTERED, "PRIORITY_SNAPSHOT_AT_REGISTRATION", System.currentTimeMillis()));
        if (isOpen(instance)) queueOnline(instance, player, true);
    }

    public void onCancelled(EventInstance instance, UUID playerId) {
        AdmissionQueue queue = queues.get(instance.id());
        if (queue != null) queue.remove(playerId);
        setStatus(instance, playerId, AdmissionStatus.CANCELLED, "PLAYER_CANCELLED");
        notifyQueuePositions(instance, false);
    }

    public boolean canCancel(EventInstance instance, UUID playerId) {
        AdmissionStatus status = status(instance, playerId);
        return status != AdmissionStatus.ADMITTED && status != AdmissionStatus.PARTICIPATING && status != AdmissionStatus.LEFT_FORFEITED;
    }

    public void onPlayerAvailable(EventInstance instance, Player player) {
        if (!isOpen(instance) || !instance.registeredPlayers().contains(player.getUniqueId())) return;
        AdmissionStatus status = status(instance, player.getUniqueId());
        if (status.blocksRejoin() || status == AdmissionStatus.PARTICIPATING || status == AdmissionStatus.ADMITTED) return;
        queueOnline(instance, player, true);
    }

    public void markAdmitted(EventInstance instance, UUID playerId) {
        AdmissionQueue queue = queues.get(instance.id());
        if (queue != null) queue.remove(playerId);
        setStatus(instance, playerId, AdmissionStatus.ADMITTED, "CAPACITY_RESERVED");
    }

    public void markParticipating(EventInstance instance, UUID playerId) {
        setStatus(instance, playerId, AdmissionStatus.PARTICIPATING, "JOIN_SUCCEEDED");
    }

    public void requeueAfterJoinFailure(EventInstance instance, UUID playerId, String reason) {
        AdmissionEntry entry = state(instance, playerId).orElse(null);
        if (entry == null) return;
        AdmissionEntry queued = entry.withStatus(AdmissionStatus.QUEUED, reason);
        remember(instance, queued);
        queues.computeIfAbsent(instance.id(), ignored -> new AdmissionQueue()).add(queued);
    }

    public void reject(EventInstance instance, UUID playerId, String reason) {
        AdmissionQueue queue = queues.get(instance.id());
        if (queue != null) queue.remove(playerId);
        registrations.forfeit(instance, playerId, "REJECTED");
        setStatus(instance, playerId, AdmissionStatus.REJECTED, reason);
    }

    public void forfeit(EventInstance instance, UUID playerId, String reason) {
        AdmissionQueue queue = queues.get(instance.id());
        if (queue != null) queue.remove(playerId);
        registrations.forfeit(instance, playerId, "LEFT_FORFEITED");
        setStatus(instance, playerId, AdmissionStatus.LEFT_FORFEITED, reason);
    }

    public Optional<AdmissionEntry> nextOnline(EventInstance instance) {
        AdmissionQueue queue = queues.get(instance.id());
        if (queue == null) return Optional.empty();
        return queue.firstMatching(playerId -> {
            Player player = Bukkit.getPlayer(playerId);
            return player != null && player.isOnline();
        });
    }

    /** Ordered online candidates snapshot used by capacity fill to avoid re-sorting/scanning from scratch per slot. */
    public List<AdmissionEntry> onlineCandidates(EventInstance instance) {
        AdmissionQueue queue = queues.get(instance.id());
        if (queue == null || queue.isEmpty()) return List.of();
        List<AdmissionEntry> result = new ArrayList<>();
        for (AdmissionEntry entry : queue.ordered()) {
            Player player = Bukkit.getPlayer(entry.playerId());
            if (player != null && player.isOnline()) result.add(entry);
        }
        return List.copyOf(result);
    }

    public int queueSize(EventInstance instance) {
        AdmissionQueue queue = queues.get(instance.id());
        return queue == null ? 0 : queue.size();
    }

    public int position(EventInstance instance, UUID playerId) {
        AdmissionQueue queue = queues.get(instance.id());
        return queue == null ? -1 : queue.position(playerId);
    }

    /** Pre-admission ranking uses the permission priority snapshotted at registration time, also for offline players. */
    public int registrationPosition(EventInstance instance, UUID playerId) {
        if (!instance.registeredPlayers().contains(playerId)) return -1;
        List<AdmissionEntry> provisional = new ArrayList<>();
        for (UUID id : instance.registeredPlayers()) {
            AdmissionEntry persisted = state(instance, id).orElse(null);
            EventQueuePriority priority;
            if (persisted != null) {
                priority = persisted.priority();
            } else {
                // Compatibility fallback for registrations created before priority snapshots existed.
                Player online = Bukkit.getPlayer(id);
                priority = online != null && online.isOnline() ? priorityResolver.resolve(online) : EventQueuePriority.NORMAL;
            }
            provisional.add(new AdmissionEntry(id, instance.registrationName(id), instance.registrationTime(id), priority,
                    AdmissionStatus.REGISTERED, "", 0));
        }
        provisional.sort(AdmissionQueue.ORDER);
        for (int i = 0; i < provisional.size(); i++) if (provisional.get(i).playerId().equals(playerId)) return i + 1;
        return -1;
    }

    public AdmissionStatus status(EventInstance instance, UUID playerId) {
        return state(instance, playerId).map(AdmissionEntry::status)
                .orElse(instance.registeredPlayers().contains(playerId) ? AdmissionStatus.REGISTERED : null);
    }

    public String priorityName(EventInstance instance, UUID playerId) {
        return state(instance, playerId).map(e -> e.priority().displayName()).orElse("-");
    }

    public boolean blocksRejoin(EventInstance instance, UUID playerId) {
        AdmissionStatus status = status(instance, playerId);
        return status != null && status.blocksRejoin();
    }

    public boolean queued(EventInstance instance, UUID playerId) { return status(instance, playerId) == AdmissionStatus.QUEUED; }

    public void notifyQueuePositions(EventInstance instance, boolean force) {
        AdmissionQueue queue = queues.get(instance.id());
        if (queue == null || queue.isEmpty()) return;
        List<AdmissionEntry> ordered = queue.ordered();
        for (int index = 0; index < ordered.size(); index++) {
            AdmissionEntry entry = ordered.get(index);
            Player player = Bukkit.getPlayer(entry.playerId());
            if (player == null || !player.isOnline()) continue;
            int position = index + 1;
            String key = instance.id() + ":" + player.getUniqueId();
            NotificationState previous = notifications.get(key);
            long now = System.currentTimeMillis();
            if (!force && previous != null && previous.position == position && now - previous.at < POSITION_MESSAGE_THROTTLE_MS) continue;
            if (!force && previous != null && now - previous.at < POSITION_MESSAGE_THROTTLE_MS) continue;
            notifications.put(key, new NotificationState(position, now));
            player.sendMessage(color("&eJesteś &f" + position + ". &ew kolejce do wydarzenia &f" + instance.definition().displayName() +
                    "&e. Priorytet: &f" + entry.priority().displayName() + "&e. Jeśli zwolni się miejsce, zostaniesz automatycznie dołączony."));
        }
    }

    private void queueOnline(EventInstance instance, Player player, boolean notify) {
        if (!registrations.checkEligibility(player, instance).success()) {
            reject(instance, player.getUniqueId(), "ELIGIBILITY_FAILED_AT_ADMISSION");
            player.sendMessage(color("&cNie spełniasz już wymagań wydarzenia i nie możesz zostać dopuszczony."));
            return;
        }
        AdmissionEntry existing = state(instance, player.getUniqueId()).orElse(null);
        EventQueuePriority priority = existing != null ? existing.priority() : priorityResolver.resolve(player);
        AdmissionEntry queued = new AdmissionEntry(player.getUniqueId(), player.getName(), instance.registrationTime(player.getUniqueId()),
                priority, AdmissionStatus.QUEUED, "WAITING_FOR_CAPACITY", System.currentTimeMillis());
        remember(instance, queued);
        queues.computeIfAbsent(instance.id(), ignored -> new AdmissionQueue()).add(queued);
        if (notify) notifyQueuePositions(instance, true);
    }

    private Optional<AdmissionEntry> state(EventInstance instance, UUID playerId) {
        return Optional.ofNullable(states.getOrDefault(instance.id(), Map.of()).get(playerId));
    }

    private void setStatus(EventInstance instance, UUID playerId, AdmissionStatus status, String reason) {
        AdmissionEntry old = state(instance, playerId).orElse(new AdmissionEntry(playerId, instance.registrationName(playerId),
                instance.registrationTime(playerId), EventQueuePriority.NORMAL, AdmissionStatus.REGISTERED, "", System.currentTimeMillis()));
        remember(instance, old.withStatus(status, reason));
    }

    private void rememberRuntimeOnly(EventInstance instance, AdmissionEntry entry) {
        states.computeIfAbsent(instance.id(), ignored -> new HashMap<>()).put(entry.playerId(), entry);
    }

    private void remember(EventInstance instance, AdmissionEntry entry) {
        rememberRuntimeOnly(instance, entry);
        UUID instanceId = instance.id();
        AdmissionEntry snapshot = entry;
        String lane = "player:" + instanceId + ":" + snapshot.playerId();
        persistence.fireAndForget(lane, "admission:" + instanceId + ":" + snapshot.playerId(), () -> repository.upsert(instanceId, snapshot));
    }

    private static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private record NotificationState(int position, long at) { }
}
