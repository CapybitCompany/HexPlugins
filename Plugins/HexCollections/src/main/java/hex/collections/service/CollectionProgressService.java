package hex.collections.service;

import hex.collections.api.*;
import hex.collections.config.CollectionRegistry;
import hex.collections.config.CollectionsSettings;
import hex.collections.database.CollectionRepository;
import hex.collections.event.CollectionLevelUpEvent;
import hex.collections.event.CollectionProgressAddEvent;
import hex.collections.event.TownCollectionResetEvent;
import hex.collections.model.CollectionDefinition;
import hex.collections.model.CollectionLevel;
import hex.collections.model.CollectionScalingState;
import hex.collections.model.SourceRule;
import hex.core.api.HexApi;
import hex.towns.api.TownsApi;
import hex.towns.api.TownPurgeContext;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class CollectionProgressService implements HexCollectionsApi {
    private final Plugin plugin;
    private final HexApi hex;
    private final TownsApi towns;
    private final CollectionRepository repository;
    private final CollectionCache cache = new CollectionCache();
    private final ConcurrentMap<UUID, ConcurrentMap<String, CollectionScalingState>> scalingCache = new ConcurrentHashMap<>();
    /** Tombstones live for the rest of the boot; a destroyed town UUID must never accept queued writes again. */
    private final Set<UUID> purgingTowns = ConcurrentHashMap.newKeySet();
    /** Per-town lifecycle lock drains writes that were already executing when purge installs its tombstone. */
    private final ConcurrentMap<UUID, java.util.concurrent.locks.ReentrantReadWriteLock> lifecycleLocks = new ConcurrentHashMap<>();
    /** Serializes read-modify-write mutations of one town's in-memory progress/scaling state. */
    private final ConcurrentMap<UUID, java.util.concurrent.locks.ReentrantLock> mutationLocks = new ConcurrentHashMap<>();
    /** Serializes DB snapshots per town so an older flush cannot finish after a newer one. */
    private final ConcurrentMap<UUID, java.util.concurrent.locks.ReentrantLock> persistenceLocks = new ConcurrentHashMap<>();
    private final Set<UUID> flushInFlight = ConcurrentHashMap.newKeySet();
    /** Global gate used only for plugin shutdown so updates that already passed the entry point are drained. */
    private final Object mutationLifecycleMonitor = new Object();
    private int inFlightMutations;
    private volatile boolean acceptingUpdates = true;
    private final CollectionRewardService rewards;
    private volatile CollectionRegistry registry;
    private volatile CollectionsSettings settings;

    public CollectionProgressService(Plugin plugin, HexApi hex, TownsApi towns, CollectionRepository repository, CollectionRegistry registry, CollectionsSettings settings) {
        this.plugin = plugin;
        this.hex = hex;
        this.towns = towns;
        this.repository = repository;
        this.registry = registry;
        this.settings = settings;
        this.rewards = new CollectionRewardService(plugin);
    }

    public void reload(CollectionRegistry registry, CollectionsSettings settings) {
        this.registry = registry;
        this.settings = settings;
    }

    public CollectionRegistry registry() { return registry; }
    public CollectionsSettings settings() { return settings; }

    @Override public long getAmount(UUID townId, String collectionId) { return progress(townId, collectionId).amount(); }
    @Override public int getLevel(UUID townId, String collectionId) { return progress(townId, collectionId).level(); }
    @Override public boolean hasUnlocked(UUID townId, String collectionId, int level) { return getLevel(townId, collectionId) >= level; }

    @Override public int getMaxLevel(String collectionId) {
        CollectionDefinition def = registry.find(collectionId).orElse(null);
        if (def == null || def.levels().isEmpty()) return 0;
        return def.levels().stream().mapToInt(CollectionLevel::level).max().orElse(0);
    }

    @Override public long getNextLevelRequirement(UUID townId, String collectionId) {
        CollectionDefinition def = registry.find(collectionId).orElse(null);
        if (def == null) return 0L;
        CollectionProgress current = progress(townId, def.id());
        int nextLevel = current.level() + 1;
        if (nextLevel > getMaxLevel(def.id())) return 0L;
        return scaledRequirement(townId, def, nextLevel);
    }

    @Override public long getRequirementForLevel(UUID townId, String collectionId, int level) {
        CollectionDefinition def = registry.find(collectionId).orElse(null);
        if (def == null || level <= 0 || level > getMaxLevel(def.id())) return 0L;
        return scaledRequirement(townId, def, level);
    }

    @Override public long getRemainingToNextLevel(UUID townId, String collectionId) {
        long required = getNextLevelRequirement(townId, collectionId);
        if (required <= 0L) return 0L;
        return Math.max(0L, required - getAmount(townId, collectionId));
    }

    @Override public double getProgressPercent(UUID townId, String collectionId) {
        CollectionDefinition def = registry.find(collectionId).orElse(null);
        if (def == null) return 0D;
        CollectionProgress p = progress(townId, def.id());
        int nextLevel = p.level() + 1;
        if (nextLevel > getMaxLevel(def.id())) return 100D;
        long req = scaledRequirement(townId, def, nextLevel);
        return req <= 0L ? 100D : Math.min(100D, p.amount() * 100D / req);
    }

    @Override public CollectionAddResult addProgress(CollectionProgressContext context) {
        if (context == null || context.townId() == null || context.collectionId() == null || context.amount() <= 0L) {
            return CollectionAddResult.denied("bad-context");
        }
        if (!beginMutation()) return CollectionAddResult.denied("plugin-shutdown");
        UUID townId = context.townId();
        try {
            var lifecycle = lifecycleLock(townId).readLock();
            lifecycle.lock();
            try {
                // Re-check after entering the lifecycle lock. A purge takes the write lock, so either
                // this update fully finishes first or it is rejected after the PURGING tombstone lands.
                if (purgingTowns.contains(townId)) return CollectionAddResult.denied("town-purging");
                var mutation = mutationLock(townId);
                mutation.lock();
                try {
                    return addProgressActive(context);
                } finally {
                    mutation.unlock();
                }
            } finally {
                lifecycle.unlock();
            }
        } finally {
            endMutation();
        }
    }

    private CollectionAddResult addProgressActive(CollectionProgressContext context) {
        CollectionDefinition def = registry.find(context.collectionId()).orElse(null);
        if (def == null) return CollectionAddResult.denied("unknown-collection");
        if (!def.validSources().contains(context.source())) return CollectionAddResult.denied("source-denied");
        SourceRule rule = def.sourceRules().get(context.source());
        if (CollectionRegistry.requiresMaterialRule(context.source()) && rule == null) {
            return CollectionAddResult.denied("missing-source-rule");
        }
        if (rule != null && context.location() != null && !rule.worldAllowed(context.location().getWorld().getName())) {
            return CollectionAddResult.denied("world-denied");
        }

        CollectionCache.TownCollectionData data = data(context.townId());
        CollectionProgress old = data.collections().getOrDefault(def.id(), CollectionProgress.empty(def.id()));
        long amount;
        try {
            amount = Math.addExact(old.amount(), context.amount());
        } catch (ArithmeticException overflow) {
            amount = Long.MAX_VALUE;
        }

        int level = old.level();
        int maxLevel = getMaxLevel(def.id());
        List<Integer> unlocked = new ArrayList<>();
        while (level < maxLevel) {
            int targetLevel = level + 1;
            long required = scaledRequirement(context.townId(), def, targetLevel);
            if (required <= 0L || amount < required) break;
            level = targetLevel;
            unlocked.add(level);
            if (level < maxLevel) {
                resetScalingTarget(context.townId(), def, level + 1);
            }
        }

        data.collections().put(def.id(), new CollectionProgress(def.id(), amount, level));
        data.markDirty();

        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.getPluginManager().callEvent(new CollectionProgressAddEvent(context.townId(), context.playerUuid(), def.id(), context.amount())));

        for (int unlockedLevel : unlocked) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.getPluginManager().callEvent(new CollectionLevelUpEvent(context.townId(), context.playerUuid(), def.id(), unlockedLevel)));
            if (context.triggerRewards()) {
                rewards.unlock(context.townId(), context.playerUuid(), def, unlockedLevel);
            }
        }

        if (settings.flushOnLevelUp() && level > old.level()) {
            flushTown(context.townId());
        }
        return new CollectionAddResult(true, "ok", old.amount(), amount, old.level(), level, unlocked);
    }

    public void onTownMemberJoined(UUID townId) {
        if (townId == null || purgingTowns.contains(townId) || !beginMutation()) return;
        try {
            var lifecycle = lifecycleLock(townId).readLock();
            lifecycle.lock();
            try {
                if (purgingTowns.contains(townId)) return;
                var mutation = mutationLock(townId);
                mutation.lock();
                try {
                    CollectionCache.TownCollectionData townData = data(townId);
                    ConcurrentMap<String, CollectionScalingState> states = scalingData(townId);
                    int members = currentMemberCount(townId);
                    initializeScalingTargets(townId, townData, states);

                    for (CollectionDefinition def : registry.all()) {
                        if (def.cityScaling() == null || !def.cityScaling().enabled()) continue;
                        CollectionProgress current = townData.collections().getOrDefault(def.id(), CollectionProgress.empty(def.id()));
                        int maxLevel = getMaxLevel(def.id());
                        if (maxLevel <= 0 || current.level() >= maxLevel) continue;
                        int targetLevel = current.level() + 1;
                        CollectionScalingState state = states.get(def.id());
                        if (state == null || state.targetLevel() != targetLevel) {
                            state = new CollectionScalingState(def.id(), targetLevel, members);
                        } else {
                            state = state.withMembers(members);
                        }
                        states.put(def.id(), state);
                        persistScaling(townId, state);
                    }
                } finally {
                    mutation.unlock();
                }
            } finally {
                lifecycle.unlock();
            }
        } finally {
            endMutation();
        }
    }

    @Override public void loadTown(UUID townId) {
        if (townId == null || purgingTowns.contains(townId) || cache.contains(townId)) return;
        data(townId);
    }

    @Override public void flushTown(UUID townId) {
        if (townId == null || purgingTowns.contains(townId)) return;
        var lifecycle = lifecycleLock(townId).readLock();
        lifecycle.lock();
        try {
            if (purgingTowns.contains(townId)) return;
            var persistence = persistenceLock(townId);
            persistence.lock();
            try {
                CollectionCache.TownCollectionData townData;
                Map<String, CollectionProgress> snapshot;
                long snapshotVersion;
                var mutation = mutationLock(townId);
                mutation.lock();
                try {
                    townData = data(townId);
                    if (!townData.dirty()) return;
                    snapshot = new HashMap<>(townData.collections());
                    snapshotVersion = townData.mutationVersion();
                } finally {
                    mutation.unlock();
                }

                repository.upsertTown(townId, snapshot);

                mutation.lock();
                try {
                    // A newer increment may have happened while the DB write was in flight.
                    // Only the exact generation that was persisted may become clean.
                    townData.markCleanIfVersion(snapshotVersion);
                } finally {
                    mutation.unlock();
                }
            } finally {
                persistence.unlock();
            }
        } finally {
            lifecycle.unlock();
        }
    }

    public void flushDirty() {
        if (!acceptingUpdates && cache.dirtySnapshot().isEmpty()) return;
        cache.dirtySnapshot().keySet().stream()
                .filter(id -> !purgingTowns.contains(id))
                .filter(flushInFlight::add)
                .forEach(id -> hex.db().asyncRun(() -> flushTown(id))
                        .whenComplete((ignored, error) -> {
                            flushInFlight.remove(id);
                            if (error != null) {
                                plugin.getLogger().severe("Collection flush failed for town=" + id + ": " + rootMessage(error));
                            }
                        }));
    }

    /**
     * Stops new progress mutations and synchronously persists every dirty town. Called during
     * plugin disable after the periodic scheduler has been cancelled.
     */
    public void shutdownAndFlush() {
        stopAcceptingAndDrainMutations(5_000L);
        Map<UUID, CollectionCache.TownCollectionData> dirty = cache.dirtySnapshot();
        int failed = 0;
        for (UUID townId : dirty.keySet()) {
            if (purgingTowns.contains(townId)) continue;
            try {
                flushTown(townId);
            } catch (Throwable error) {
                failed++;
                plugin.getLogger().severe("Final collection flush failed for town=" + townId
                        + " dirtyCollections=" + dirty.get(townId).collections().size()
                        + ": " + rootMessage(error));
            }
        }
        if (failed > 0) {
            plugin.getLogger().severe("HexCollections shutdown completed with " + failed + " town(s) still not safely persisted.");
        }
    }

    @Override public void unloadTown(UUID townId) {
        if (townId != null) {
            if (!purgingTowns.contains(townId)) flushTown(townId);
            cache.remove(townId);
            scalingCache.remove(townId);
        }
    }

    @Override public void deleteTownCollectionData(UUID townId) {
        if (townId == null) return;
        purgingTowns.add(townId);
        var lock = lifecycleLock(townId).writeLock();
        lock.lock();
        try {
            cache.remove(townId);
            scalingCache.remove(townId);
            repository.purgeTownVerified(townId);
        } finally {
            lock.unlock();
        }
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new TownCollectionResetEvent(townId)));
    }

    /** Async namespace cleanup using the durable HexTowns snapshot. Idempotent and resurrection-safe. */
    public java.util.concurrent.CompletableFuture<Void> purgeTownData(TownPurgeContext context) {
        if (context == null || context.townUuid() == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        UUID townId = context.townUuid();
        purgingTowns.add(townId);
        // The write lock is acquired on the DB worker. It waits for a flush/scaling write that already
        // held the read lock, then performs the final verified DELETE while all new writes are fenced.
        return hex.db().asyncRun(() -> {
            var lock = lifecycleLock(townId).writeLock();
            lock.lock();
            try {
                cache.remove(townId);
                scalingCache.remove(townId);
                repository.purgeTownVerified(townId);
            } finally {
                lock.unlock();
            }
        }).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.getPluginManager().callEvent(new TownCollectionResetEvent(townId))));
    }

    @Override public Map<String, CollectionProgress> getAllCollections(UUID townId) {
        if (townId == null) return Map.of();
        Map<String, CollectionProgress> result = new LinkedHashMap<>();
        Map<String, CollectionProgress> stored = data(townId).collections();
        for (CollectionDefinition definition : registry.all()) {
            result.put(definition.id(), stored.getOrDefault(definition.id(), CollectionProgress.empty(definition.id())));
        }
        return Map.copyOf(result);
    }

    @Override public List<TopCollectionEntry> top(String collectionId, int limit) {
        CollectionDefinition def = registry.find(collectionId).orElse(null);
        if (def == null) return List.of();
        return repository.top(def.id(), limit);
    }

    private long scaledRequirement(UUID townId, CollectionDefinition def, int requestedLevel) {
        long base = Math.max(0L, def.requiredFor(requestedLevel));
        if (townId == null || base <= 0L || def.cityScaling() == null || !def.cityScaling().enabled()) {
            return base;
        }

        CollectionProgress current = progress(townId, def.id());
        int maxLevel = getMaxLevel(def.id());
        int activeTarget = Math.min(maxLevel, Math.max(1, current.level() + 1));
        CollectionScalingState state = ensureScalingState(townId, def, activeTarget);
        long addon = def.cityScaling().scaledAddonFor(requestedLevel, state.effectiveMemberCount());
        try {
            return Math.addExact(base, addon);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private CollectionScalingState ensureScalingState(UUID townId, CollectionDefinition def, int targetLevel) {
        ConcurrentMap<String, CollectionScalingState> map = scalingData(townId);
        CollectionScalingState existing = map.get(def.id());
        if (existing != null && existing.targetLevel() == targetLevel) {
            return existing;
        }
        CollectionScalingState created = new CollectionScalingState(def.id(), targetLevel, currentMemberCount(townId));
        map.put(def.id(), created);
        persistScaling(townId, created);
        return created;
    }

    private void resetScalingTarget(UUID townId, CollectionDefinition def, int targetLevel) {
        if (def.cityScaling() == null || !def.cityScaling().enabled()) return;
        CollectionScalingState next = new CollectionScalingState(def.id(), targetLevel, currentMemberCount(townId));
        scalingData(townId).put(def.id(), next);
        persistScaling(townId, next);
    }

    private void persistScaling(UUID townId, CollectionScalingState state) {
        if (townId == null || state == null || purgingTowns.contains(townId) || !acceptingUpdates) return;
        hex.db().asyncRun(() -> withTownWrite(townId, () -> repository.upsertScaling(townId, state)));
    }


    private boolean beginMutation() {
        synchronized (mutationLifecycleMonitor) {
            if (!acceptingUpdates) return false;
            inFlightMutations++;
            return true;
        }
    }

    private void endMutation() {
        synchronized (mutationLifecycleMonitor) {
            if (inFlightMutations > 0) inFlightMutations--;
            if (inFlightMutations == 0) mutationLifecycleMonitor.notifyAll();
        }
    }

    private void stopAcceptingAndDrainMutations(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (mutationLifecycleMonitor) {
            acceptingUpdates = false;
            while (inFlightMutations > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    plugin.getLogger().severe("Timed out waiting for " + inFlightMutations
                            + " in-flight collection mutation(s) during shutdown.");
                    break;
                }
                try {
                    mutationLifecycleMonitor.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    plugin.getLogger().severe("Interrupted while draining in-flight collection mutations during shutdown.");
                    break;
                }
            }
        }
    }

    private java.util.concurrent.locks.ReentrantReadWriteLock lifecycleLock(UUID townId) {
        return lifecycleLocks.computeIfAbsent(townId, ignored -> new java.util.concurrent.locks.ReentrantReadWriteLock());
    }

    private java.util.concurrent.locks.ReentrantLock mutationLock(UUID townId) {
        return mutationLocks.computeIfAbsent(townId, ignored -> new java.util.concurrent.locks.ReentrantLock());
    }

    private java.util.concurrent.locks.ReentrantLock persistenceLock(UUID townId) {
        return persistenceLocks.computeIfAbsent(townId, ignored -> new java.util.concurrent.locks.ReentrantLock());
    }

    private void withTownWrite(UUID townId, Runnable write) {
        if (townId == null || write == null || purgingTowns.contains(townId)) return;
        var lock = lifecycleLock(townId).readLock();
        lock.lock();
        try {
            if (!purgingTowns.contains(townId)) write.run();
        } finally {
            lock.unlock();
        }
    }

    private int currentMemberCount(UUID townId) {
        if (townId == null) return 1;
        try {
            return Math.max(1, towns.memberCount(townId));
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private ConcurrentMap<String, CollectionScalingState> scalingData(UUID townId) {
        if (townId == null || purgingTowns.contains(townId)) return new ConcurrentHashMap<>();
        var mutation = mutationLock(townId);
        mutation.lock();
        try {
            return scalingCache.computeIfAbsent(townId, id -> new ConcurrentHashMap<>(repository.loadScalingTown(id)));
        } finally {
            mutation.unlock();
        }
    }

    private void initializeScalingTargets(UUID townId, CollectionCache.TownCollectionData townData, ConcurrentMap<String, CollectionScalingState> states) {
        if (townId == null || townData == null || states == null) return;
        int members = currentMemberCount(townId);
        for (CollectionDefinition def : registry.all()) {
            if (def.cityScaling() == null || !def.cityScaling().enabled()) continue;
            CollectionProgress current = townData.collections().getOrDefault(def.id(), CollectionProgress.empty(def.id()));
            int maxLevel = getMaxLevel(def.id());
            if (maxLevel <= 0 || current.level() >= maxLevel) continue;
            int targetLevel = current.level() + 1;
            CollectionScalingState existing = states.get(def.id());
            if (existing != null && existing.targetLevel() == targetLevel) continue;
            CollectionScalingState created = new CollectionScalingState(def.id(), targetLevel, members);
            states.put(def.id(), created);
            persistScaling(townId, created);
        }
    }

    private CollectionProgress progress(UUID townId, String collectionId) {
        CollectionDefinition def = registry.find(collectionId).orElse(null);
        if (townId == null || def == null) return CollectionProgress.empty(collectionId);
        return data(townId).collections().getOrDefault(def.id(), CollectionProgress.empty(def.id()));
    }

    private CollectionCache.TownCollectionData data(UUID townId) {
        if (townId == null) return cache.getOrCreate(new UUID(0L, 0L));
        if (purgingTowns.contains(townId)) return new CollectionCache.TownCollectionData(townId);
        var mutation = mutationLock(townId);
        mutation.lock();
        try {
            CollectionCache.TownCollectionData existing = cache.get(townId);
            if (existing != null) return existing;
            CollectionCache.TownCollectionData loaded = cache.put(townId, repository.loadTown(townId));
            ConcurrentMap<String, CollectionScalingState> states = new ConcurrentHashMap<>(repository.loadScalingTown(townId));
            ConcurrentMap<String, CollectionScalingState> activeStates = scalingCache.putIfAbsent(townId, states);
            if (activeStates == null) activeStates = states;
            initializeScalingTargets(townId, loaded, activeStates);
            return loaded;
        } finally {
            mutation.unlock();
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
