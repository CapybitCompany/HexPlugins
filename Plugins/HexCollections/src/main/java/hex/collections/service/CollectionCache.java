package hex.collections.service;

import hex.collections.api.CollectionProgress;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class CollectionCache {
    private final ConcurrentMap<UUID, TownCollectionData> towns = new ConcurrentHashMap<>();

    public TownCollectionData getOrCreate(UUID townId) {
        return towns.computeIfAbsent(townId, TownCollectionData::new);
    }

    public boolean contains(UUID townId) {
        return townId != null && towns.containsKey(townId);
    }

    public TownCollectionData get(UUID townId) {
        return townId == null ? null : towns.get(townId);
    }

    public TownCollectionData put(UUID townId, Map<String, CollectionProgress> progress) {
        TownCollectionData data = new TownCollectionData(townId);
        data.collections.putAll(progress);
        towns.put(townId, data);
        return data;
    }

    public TownCollectionData remove(UUID townId) {
        return towns.remove(townId);
    }

    public Map<UUID, TownCollectionData> dirtySnapshot() {
        Map<UUID, TownCollectionData> result = new HashMap<>();
        towns.forEach((id, data) -> { if (data.dirty) result.put(id, data); });
        return result;
    }

    public Map<UUID, TownCollectionData> snapshot() {
        return Map.copyOf(towns);
    }

    public static final class TownCollectionData {
        private final UUID townId;
        private final ConcurrentMap<String, CollectionProgress> collections = new ConcurrentHashMap<>();
        private volatile boolean dirty;
        private volatile long lastModified;
        /**
         * Monotonic in-memory generation. A flush snapshots this value and may only clear the
         * dirty flag when no newer mutation happened while the DB write was in flight.
         */
        private volatile long mutationVersion;

        TownCollectionData(UUID townId) {
            this.townId = townId;
            this.lastModified = System.currentTimeMillis();
        }

        public UUID townId() { return townId; }
        public Map<String, CollectionProgress> collections() { return collections; }
        public boolean dirty() { return dirty; }
        public long mutationVersion() { return mutationVersion; }

        public long markDirty() {
            mutationVersion++;
            dirty = true;
            lastModified = System.currentTimeMillis();
            return mutationVersion;
        }

        public boolean markCleanIfVersion(long expectedVersion) {
            if (mutationVersion != expectedVersion) return false;
            dirty = false;
            return true;
        }

        public long lastModified() { return lastModified; }
    }
}
