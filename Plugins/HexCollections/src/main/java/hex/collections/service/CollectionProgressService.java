package hex.collections.service;

import hex.collections.api.*;
import hex.collections.config.CollectionRegistry;
import hex.collections.config.CollectionsSettings;
import hex.collections.database.CollectionRepository;
import hex.collections.event.CollectionLevelUpEvent;
import hex.collections.event.CollectionProgressAddEvent;
import hex.collections.event.TownCollectionResetEvent;
import hex.collections.model.CollectionDefinition;
import hex.collections.model.SourceRule;
import hex.core.api.HexApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.util.*;

public final class CollectionProgressService implements HexCollectionsApi {
    private final Plugin plugin;
    private final HexApi hex;
    private final CollectionRepository repository;
    private final CollectionCache cache = new CollectionCache();
    private final CollectionRewardService rewards;
    private volatile CollectionRegistry registry;
    private volatile CollectionsSettings settings;

    public CollectionProgressService(Plugin plugin, HexApi hex, CollectionRepository repository, CollectionRegistry registry, CollectionsSettings settings) {
        this.plugin = plugin; this.hex = hex; this.repository = repository; this.registry = registry; this.settings = settings; this.rewards = new CollectionRewardService(plugin);
    }
    public void reload(CollectionRegistry registry, CollectionsSettings settings) { this.registry = registry; this.settings = settings; }
    public CollectionRegistry registry() { return registry; }
    public CollectionsSettings settings() { return settings; }

    @Override public long getAmount(UUID townId, String collectionId) { return progress(townId, collectionId).amount(); }
    @Override public int getLevel(UUID townId, String collectionId) { return progress(townId, collectionId).level(); }
    @Override public boolean hasUnlocked(UUID townId, String collectionId, int level) { return getLevel(townId, collectionId) >= level; }
    @Override public double getProgressPercent(UUID townId, String collectionId) {
        CollectionDefinition def = registry.find(collectionId).orElse(null); if (def == null) return 0D;
        CollectionProgress p = progress(townId, def.id()); long req = def.nextRequired(p.level());
        return req <= 0L ? 100D : Math.min(100D, p.amount() * 100D / req);
    }

    @Override public CollectionAddResult addProgress(CollectionProgressContext context) {
        if (context == null || context.townId() == null || context.collectionId() == null || context.amount() <= 0L) return CollectionAddResult.denied("bad-context");
        CollectionDefinition def = registry.find(context.collectionId()).orElse(null);
        if (def == null) return CollectionAddResult.denied("unknown-collection");
        if (!def.validSources().contains(context.source())) return CollectionAddResult.denied("source-denied");
        SourceRule rule = def.sourceRules().get(context.source());
        if (rule != null && context.location() != null && !rule.worldAllowed(context.location().getWorld().getName())) return CollectionAddResult.denied("world-denied");
        CollectionCache.TownCollectionData data = cache.getOrCreate(context.townId());
        CollectionProgress old = data.collections().getOrDefault(def.id(), CollectionProgress.empty(def.id()));
        long amount = old.amount() + context.amount(); int level = def.levelFor(amount);
        data.collections().put(def.id(), new CollectionProgress(def.id(), amount, level)); data.markDirty();
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new CollectionProgressAddEvent(context.townId(), context.playerUuid(), def.id(), context.amount())));
        List<Integer> unlocked = new ArrayList<>();
        for (int i = old.level() + 1; i <= level; i++) { int unlockedLevel = i; unlocked.add(i); Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new CollectionLevelUpEvent(context.townId(), context.playerUuid(), def.id(), unlockedLevel))); if (context.triggerRewards()) rewards.unlock(context.townId(), context.playerUuid(), def, i); }
        if (settings.flushOnLevelUp() && level > old.level()) flushTown(context.townId());
        return new CollectionAddResult(true, "ok", old.amount(), amount, old.level(), level, unlocked);
    }

    @Override public void loadTown(UUID townId) { if (townId != null) cache.put(townId, repository.loadTown(townId)); }
    @Override public void flushTown(UUID townId) { if (townId != null) { var data = cache.getOrCreate(townId); repository.upsertTown(townId, data.collections()); data.markClean(); } }
    public void flushDirty() { cache.dirtySnapshot().keySet().forEach(id -> hex.db().asyncRun(() -> flushTown(id))); }
    @Override public void unloadTown(UUID townId) { if (townId != null) { flushTown(townId); cache.remove(townId); } }
    @Override public void deleteTownCollectionData(UUID townId) { if (townId != null) { cache.remove(townId); repository.purgeTown(townId); Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new TownCollectionResetEvent(townId))); } }
    @Override public Map<String, CollectionProgress> getAllCollections(UUID townId) { return Map.copyOf(cache.getOrCreate(townId).collections()); }


    public List<TopCollectionEntry> top(String collectionId, int limit) {
        CollectionDefinition def = registry.find(collectionId).orElse(null);
        if (def == null) {
            return List.of();
        }
        return repository.top(def.id(), limit);
    }

    private CollectionProgress progress(UUID townId, String collectionId) {
        CollectionDefinition def = registry.find(collectionId).orElse(null);
        if (townId == null || def == null) return CollectionProgress.empty(collectionId);
        return cache.getOrCreate(townId).collections().getOrDefault(def.id(), CollectionProgress.empty(def.id()));
    }
}


