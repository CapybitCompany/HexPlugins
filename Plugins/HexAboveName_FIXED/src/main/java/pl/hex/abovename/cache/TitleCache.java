package pl.hex.abovename.cache;

import pl.hex.abovename.storage.StoredTitle;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory cache of player titles. The repeating Bukkit update
 * task reads from this cache exclusively — never from the storage layer.
 *
 * Writes happen on the main thread (commands, reload). Reads happen on the
 * main thread (update task, events). {@link ConcurrentHashMap} is used so
 * the cache stays safe even if a future change adds off-main-thread reads.
 */
public final class TitleCache {

    private final Map<UUID, StoredTitle> byUuid = new ConcurrentHashMap<>();

    public boolean contains(UUID uuid) {
        return uuid != null && byUuid.containsKey(uuid);
    }

    public Optional<StoredTitle> get(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byUuid.get(uuid));
    }

    public String titleOf(UUID uuid) {
        StoredTitle stored = byUuid.get(uuid);
        return stored == null ? null : stored.title();
    }

    public void put(StoredTitle stored) {
        if (stored == null) {
            return;
        }
        byUuid.put(stored.uuid(), stored);
    }

    public void remove(UUID uuid) {
        if (uuid != null) {
            byUuid.remove(uuid);
        }
    }

    public void clear() {
        byUuid.clear();
    }

    public void replaceAll(Map<UUID, StoredTitle> snapshot) {
        byUuid.clear();
        if (snapshot != null && !snapshot.isEmpty()) {
            byUuid.putAll(snapshot);
        }
    }

    public Collection<StoredTitle> all() {
        return Map.copyOf(byUuid).values();
    }

    public int size() {
        return byUuid.size();
    }

    public Optional<UUID> findUuidByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String needle = name.toLowerCase(Locale.ROOT);
        for (StoredTitle stored : byUuid.values()) {
            if (stored.name().toLowerCase(Locale.ROOT).equals(needle)) {
                return Optional.of(stored.uuid());
            }
        }
        return Optional.empty();
    }
}
