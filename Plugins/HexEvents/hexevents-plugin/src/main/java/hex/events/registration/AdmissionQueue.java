package hex.events.registration;

import java.util.*;
import java.util.function.Predicate;

/**
 * In-memory admission queue. Ordering is maintained incrementally, so taking a
 * snapshot is O(n) and removal is O(log n). Already admitted players are
 * removed and can never be displaced by a later higher-priority registration.
 */
public final class AdmissionQueue {
    public static final Comparator<AdmissionEntry> ORDER = Comparator
            .comparingInt((AdmissionEntry e) -> e.priority().weight()).reversed()
            .thenComparingLong(AdmissionEntry::registeredAt)
            .thenComparing(e -> e.playerId().toString());

    private final NavigableSet<AdmissionEntry> queue = new TreeSet<>(ORDER);
    private final Map<UUID, AdmissionEntry> byPlayer = new HashMap<>();

    public void add(AdmissionEntry entry) {
        remove(entry.playerId());
        byPlayer.put(entry.playerId(), entry);
        queue.add(entry);
    }

    public boolean remove(UUID playerId) {
        AdmissionEntry old = byPlayer.remove(playerId);
        return old != null && queue.remove(old);
    }

    public boolean contains(UUID playerId) { return byPlayer.containsKey(playerId); }
    public int size() { return queue.size(); }
    public boolean isEmpty() { return queue.isEmpty(); }

    public Optional<AdmissionEntry> entry(UUID playerId) { return Optional.ofNullable(byPlayer.get(playerId)); }

    public List<AdmissionEntry> ordered() { return List.copyOf(queue); }

    public int position(UUID playerId) {
        int index = 1;
        for (AdmissionEntry entry : queue) {
            if (entry.playerId().equals(playerId)) return index;
            index++;
        }
        return -1;
    }

    public Optional<AdmissionEntry> firstMatching(Predicate<UUID> predicate) {
        for (AdmissionEntry entry : queue) if (predicate.test(entry.playerId())) return Optional.of(entry);
        return Optional.empty();
    }

    public void clear() {
        queue.clear();
        byPlayer.clear();
    }
}
