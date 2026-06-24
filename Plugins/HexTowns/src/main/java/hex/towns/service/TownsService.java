package hex.towns.service;

import hex.core.api.HexApi;
import hex.core.api.messaging.HexMessage;
import hex.core.api.messaging.HexMessageBus;
import hex.core.api.messaging.HexMessageData;
import hex.core.api.ui.UiTokens;
import hex.towns.api.Page;
import hex.towns.api.TownsListener;
import hex.towns.api.event.TownChunkClaimedEvent;
import hex.towns.api.event.TownCoopJoinedEvent;
import hex.towns.api.event.TownCoopLeftEvent;
import hex.towns.api.event.TownCreatedEvent;
import hex.towns.api.event.TownDestroyedEvent;
import hex.towns.api.event.TownRenamedEvent;
import hex.towns.config.TownsConfig;
import hex.towns.database.TownRepository;
import hex.towns.model.ChunkPos;
import hex.towns.model.Town;
import hex.towns.model.TownRole;
import hex.towns.model.TownStatus;
import hex.towns.util.ChunkKeys;
import hex.towns.util.UuidBytes;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class TownsService {
    private final Plugin plugin;
    private final HexApi api;
    private final TownRepository repository;
    private final TownDataRegistry dataRegistry;
    private final TownsConfig config;
    private final HexMessageBus messageBus;
    private final Object mutationLock = new Object();
    private final AtomicBoolean growthSyncRunning = new AtomicBoolean(false);
    private volatile BukkitTask growthSyncTask;

    private final ConcurrentMap<Long, Town> townsByInternalId = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> internalIdByTownUuid = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Membership> playerIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> chunkIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Set<ChunkPos>> chunksByTown = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Set<UUID>> membersByTown = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> worldIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, String> worldNames = new ConcurrentHashMap<>();
    private final Set<TownsListener> listeners = ConcurrentHashMap.newKeySet();

    public TownsService(Plugin plugin, HexApi api, TownRepository repository, TownDataRegistry dataRegistry, TownsConfig config) {
        this.plugin = plugin;
        this.api = api;
        this.repository = repository;
        this.dataRegistry = dataRegistry;
        this.config = config;
        this.messageBus = api.service(HexMessageBus.class).orElse(null);
    }

    public void load(TownRepository.InitialState state) {
        worldIds.clear();
        worldNames.clear();
        worldIds.putAll(state.worlds());
        state.worlds().forEach((name, id) -> worldNames.put(id, name));

        townsByInternalId.clear();
        internalIdByTownUuid.clear();
        playerIndex.clear();
        chunkIndex.clear();
        chunksByTown.clear();
        membersByTown.clear();

        for (Town town : state.towns()) {
            townsByInternalId.put(town.internalId(), town);
            internalIdByTownUuid.put(town.id(), town.internalId());
            chunksByTown.put(town.internalId(), ConcurrentHashMap.newKeySet());
            membersByTown.put(town.internalId(), ConcurrentHashMap.newKeySet());
        }
        for (TownRepository.ChunkRecord chunk : state.chunks()) {
            chunkIndex.put(ChunkKeys.chunkKey(chunk.worldId(), chunk.x(), chunk.z()), chunk.townId());
            chunksByTown.computeIfAbsent(chunk.townId(), ignored -> ConcurrentHashMap.newKeySet()).add(new ChunkPos(chunk.x(), chunk.z()));
        }
        for (TownRepository.MemberRecord member : state.members()) {
            playerIndex.put(member.playerId(), new Membership(member.townId(), member.role()));
            membersByTown.computeIfAbsent(member.townId(), ignored -> ConcurrentHashMap.newKeySet()).add(member.playerId());
        }
    }

    public void startGrowthSync() {
        stopGrowthSync();
        if (!config.growthSyncEnabled()) {
            plugin.getLogger().info("HexTowns growth_points DB sync is disabled in config.");
            return;
        }
        long interval = config.growthSyncIntervalTicks();
        growthSyncTask = Bukkit.getScheduler().runTaskTimer(plugin, () ->
                refreshGrowthFromDatabase().whenComplete((result, error) -> {
                    if (error != null) {
                        plugin.getLogger().warning("HexTowns growth_points sync failed: " + error.getMessage());
                    }
                }), interval, interval);
        plugin.getLogger().info("HexTowns growth_points DB sync enabled every " + interval + " ticks.");
    }

    public void stopGrowthSync() {
        BukkitTask task = growthSyncTask;
        if (task != null) {
            task.cancel();
            growthSyncTask = null;
        }
    }

    public CompletableFuture<GrowthSyncResult> refreshGrowthFromDatabase() {
        if (!growthSyncRunning.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(new GrowthSyncResult(0, 0, true));
        }
        return api.db().async(() -> {
            synchronized (mutationLock) {
                Map<Long, Integer> snapshot = repository.loadGrowthPoints();
                int changed = 0;
                for (Map.Entry<Long, Integer> entry : snapshot.entrySet()) {
                    Town town = townsByInternalId.get(entry.getKey());
                    if (town == null) {
                        continue;
                    }
                    int dbGrowth = entry.getValue();
                    if (town.growthPoints() != dbGrowth) {
                        town.setGrowthPoints(dbGrowth);
                        changed++;
                    }
                }
                return new GrowthSyncResult(snapshot.size(), changed, false);
            }
        }).whenComplete((result, error) -> growthSyncRunning.set(false));
    }

    public OperationResult previewCreate(Player player, String name) {
        String previewName = normalizeTownName(name, defaultTownName());
        if (!config.isWorldAllowed(player.getWorld().getName())) {
            return OperationResult.fail("towns.error.world-disabled");
        }
        if (playerIndex.containsKey(player.getUniqueId())) {
            return OperationResult.fail("towns.create.already-member");
        }
        Integer worldId = worldIds.get(player.getWorld().getName());
        if (worldId != null && isTooClose(worldId, initialChunks(player.getChunk().getX(), player.getChunk().getZ()), config.minDistanceChunks())) {
            return OperationResult.fail("towns.create.too-close", UiTokens.of("distance", String.valueOf(config.minDistanceChunks())));
        }
        return OperationResult.ok("towns.create.confirm", UiTokens.of("town", previewName));
    }

    public CompletableFuture<OperationResult> createTown(Player player, String requestedName) {
        return createTownAt(player, requestedName, player.getChunk().getX(), player.getChunk().getZ());
    }

    public CompletableFuture<OperationResult> createTownAt(Player player, String requestedName, int centerX, int centerZ) {
        UUID ownerId = player.getUniqueId();
        String worldName = player.getWorld().getName();
        String townName = normalizeTownName(requestedName, defaultTownName());

        return api.db().async(() -> {
            synchronized (mutationLock) {
                if (!config.isWorldAllowed(worldName)) {
                    return OperationResult.fail("towns.error.world-disabled");
                }
                if (playerIndex.containsKey(ownerId)) {
                    return OperationResult.fail("towns.create.already-member");
                }

                int worldId = repository.getOrCreateWorldId(worldName);
                worldIds.put(worldName, worldId);
                worldNames.put(worldId, worldName);

                List<ChunkPos> initialChunks = initialChunks(centerX, centerZ);
                if (isTooClose(worldId, initialChunks, config.minDistanceChunks())) {
                    return OperationResult.fail("towns.create.too-close", UiTokens.of("distance", String.valueOf(config.minDistanceChunks())));
                }

                UUID townUuid = UUID.randomUUID();
                long internalId = UuidBytes.internalId(townUuid);
                Town town = new Town(internalId, townUuid, ownerId, townName, worldName, worldId,
                        new ChunkPos(centerX, centerZ), config.startingGrowthPoints(), Instant.now(), TownStatus.ACTIVE);
                repository.createTown(town, initialChunks, ownerId, config.bucketSize());
                indexTown(town, initialChunks, ownerId);
                publishCreated(town, ownerId);
                return OperationResult.ok("towns.create.success", UiTokens.of("town", town.name()));
            }
        });
    }

    public CompletableFuture<OperationResult> claim(Player player) {
        UUID playerId = player.getUniqueId();
        String worldName = player.getWorld().getName();
        int chunkX = player.getChunk().getX();
        int chunkZ = player.getChunk().getZ();

        return api.db().async(() -> {
            synchronized (mutationLock) {
                Membership membership = playerIndex.get(playerId);
                if (membership == null) {
                    return OperationResult.fail("towns.error.no-town");
                }
                Town town = townsByInternalId.get(membership.townId());
                if (town == null || town.status() != TownStatus.ACTIVE) {
                    return OperationResult.fail("towns.error.no-town");
                }
                if (!town.world().equals(worldName)) {
                    return OperationResult.fail("towns.claim.world-mismatch");
                }

                ChunkPos claim = new ChunkPos(chunkX, chunkZ);
                if (chunkIndex.containsKey(ChunkKeys.chunkKey(town.worldId(), chunkX, chunkZ))) {
                    return OperationResult.fail("towns.claim.already-claimed");
                }
                Set<ChunkPos> townChunks = chunksByTown.getOrDefault(town.internalId(), Set.of());
                if (townChunks.size() >= config.maxChunks()) {
                    return OperationResult.fail("towns.claim.limit-reached", UiTokens.of("max", String.valueOf(config.maxChunks())));
                }
                if (townChunks.stream().noneMatch(existing -> existing.touchesSide(claim))) {
                    return OperationResult.fail("towns.claim.not-adjacent");
                }
                if (violatesBuffer(town.worldId(), town.internalId(), claim)) {
                    return OperationResult.fail("towns.claim.buffer-violation");
                }
                if (!town.tryConsumeGrowthPoint()) {
                    return OperationResult.fail("towns.claim.no-growth");
                }
                boolean saved = repository.addChunkAndConsumeGrowth(town, claim, config.bucketSize());
                if (!saved) {
                    town.addGrowthPoints(1);
                    return OperationResult.fail("towns.claim.no-growth");
                }
                addChunkIndex(town, claim);
                publishClaimed(town, claim, playerId);
                return OperationResult.ok("towns.claim.success", UiTokens.of("cx", String.valueOf(chunkX)).put("cz", String.valueOf(chunkZ)));
            }
        });
    }

    public CompletableFuture<OperationResult> requestCoop(Player player) {
        UUID playerId = player.getUniqueId();
        Location loc = player.getLocation();
        Optional<Town> target = townAt(loc);
        if (target.isEmpty()) {
            return CompletableFuture.completedFuture(OperationResult.fail("towns.coop.not-in-town"));
        }
        Town town = target.get();
        if (town.ownerId().equals(playerId) || isMember(playerId, town.id())) {
            return CompletableFuture.completedFuture(OperationResult.fail("towns.coop.already-member"));
        }
        if (playerIndex.containsKey(playerId)) {
            return CompletableFuture.completedFuture(OperationResult.fail("towns.coop.requester-has-town"));
        }
        return api.db().async(() -> {
            repository.upsertCoopRequest(town.internalId(), playerId);
            Player owner = Bukkit.getPlayer(town.ownerId());
            if (owner != null) {
                Bukkit.getScheduler().runTask(plugin, () -> api.ui().send(owner, "towns.coop.request-sent", UiTokens.of("player", player.getName())));
            }
            return OperationResult.ok("towns.coop.request-created", UiTokens.of("town", town.name()));
        });
    }

    public CompletableFuture<OperationResult> acceptCoop(Player owner, Player requester) {
        return acceptCoopInternal(owner, requester.getUniqueId(), requester.getName(), true);
    }

    public CompletableFuture<OperationResult> acceptCoopRequest(Player owner, UUID requesterId, String requesterName) {
        return acceptCoopInternal(owner, requesterId, requesterName, false);
    }

    private CompletableFuture<OperationResult> acceptCoopInternal(Player owner, UUID requesterId, String requesterName, boolean requireOwnerStandingInTown) {
        UUID ownerId = owner.getUniqueId();
        String ownerWorld = owner.getWorld().getName();
        int ownerChunkX = owner.getChunk().getX();
        int ownerChunkZ = owner.getChunk().getZ();
        return api.db().async(() -> {
            synchronized (mutationLock) {
                Membership ownerMembership = playerIndex.get(ownerId);
                if (ownerMembership == null || ownerMembership.role() != TownRole.OWNER) {
                    return OperationResult.fail("towns.error.not-owner");
                }
                Town town = townsByInternalId.get(ownerMembership.townId());
                if (town == null) {
                    return OperationResult.fail("towns.error.no-town");
                }
                if (requireOwnerStandingInTown && townAt(ownerWorld, ownerChunkX, ownerChunkZ).map(Town::internalId).orElse(-1L) != town.internalId()) {
                    return OperationResult.fail("towns.accept.must-stand-in-town");
                }
                if (playerIndex.containsKey(requesterId)) {
                    repository.deleteCoopRequest(town.internalId(), requesterId);
                    return OperationResult.fail("towns.coop.requester-has-town");
                }
                Set<UUID> members = membersByTown.getOrDefault(town.internalId(), Set.of());
                if (members.size() >= config.maxMembers()) {
                    return OperationResult.fail("towns.coop.full");
                }
                boolean hasRequest = repository.hasCoopRequest(town.internalId(), requesterId, config.requestTtlSeconds() * 1000L);
                if (!hasRequest) {
                    return OperationResult.fail("towns.accept.no-request");
                }
                repository.addMember(town.internalId(), requesterId, TownRole.COOP);
                repository.deleteCoopRequest(town.internalId(), requesterId);
                repository.deleteAllCoopRequestsForRequester(requesterId);
                playerIndex.put(requesterId, new Membership(town.internalId(), TownRole.COOP));
                membersByTown.computeIfAbsent(town.internalId(), ignored -> ConcurrentHashMap.newKeySet()).add(requesterId);
                publishCoopJoined(town, requesterId);
                return OperationResult.ok("towns.accept.success", UiTokens.of("player", safePlayerName(requesterId, requesterName)));
            }
        });
    }

    public CompletableFuture<OperationResult> rejectCoopRequest(Player owner, UUID requesterId, String requesterName) {
        UUID ownerId = owner.getUniqueId();
        return api.db().async(() -> {
            synchronized (mutationLock) {
                Membership ownerMembership = playerIndex.get(ownerId);
                if (ownerMembership == null || ownerMembership.role() != TownRole.OWNER) {
                    return OperationResult.fail("towns.error.not-owner");
                }
                Town town = townsByInternalId.get(ownerMembership.townId());
                if (town == null) {
                    return OperationResult.fail("towns.error.no-town");
                }
                repository.deleteCoopRequest(town.internalId(), requesterId);
                return OperationResult.ok("towns.reject.success", UiTokens.of("player", safePlayerName(requesterId, requesterName)));
            }
        });
    }

    public CompletableFuture<OperationResult> kickCoopMember(Player owner, UUID targetId, String targetName) {
        UUID ownerId = owner.getUniqueId();
        return api.db().async(() -> {
            synchronized (mutationLock) {
                Membership ownerMembership = playerIndex.get(ownerId);
                if (ownerMembership == null || ownerMembership.role() != TownRole.OWNER) {
                    return OperationResult.fail("towns.error.not-owner");
                }
                Town town = townsByInternalId.get(ownerMembership.townId());
                Membership targetMembership = playerIndex.get(targetId);
                if (town == null || targetMembership == null || targetMembership.townId() != town.internalId()) {
                    return OperationResult.fail("towns.kick.not-member");
                }
                if (targetMembership.role() == TownRole.OWNER || targetId.equals(town.ownerId())) {
                    return OperationResult.fail("towns.kick.owner");
                }
                repository.removeMember(targetId);
                playerIndex.remove(targetId);
                membersByTown.getOrDefault(town.internalId(), Set.of()).remove(targetId);
                publishCoopLeft(town, targetId, "KICK");
                publishReset(List.of(targetId), "coop_kick");
                clearOnlinePlayerData(targetId);
                return OperationResult.ok("towns.kick.success", UiTokens.of("player", safePlayerName(targetId, targetName)));
            }
        });
    }

    public CompletableFuture<OperationResult> endCoop(Player player) {
        UUID playerId = player.getUniqueId();
        return api.db().async(() -> {
            synchronized (mutationLock) {
                Membership membership = playerIndex.get(playerId);
                if (membership == null || membership.role() != TownRole.COOP) {
                    return OperationResult.fail("towns.endcoop.not-coop");
                }
                Town town = townsByInternalId.get(membership.townId());
                repository.removeMember(playerId);
                playerIndex.remove(playerId);
                membersByTown.getOrDefault(membership.townId(), Set.of()).remove(playerId);
                if (town != null) {
                    publishCoopLeft(town, playerId, "RESIGN");
                    publishReset(List.of(playerId), "endcoop");
                }
                return OperationResult.ok("towns.endcoop.success");
            }
        });
    }

    public CompletableFuture<OperationResult> destroy(Player player) {
        UUID playerId = player.getUniqueId();
        return api.db().async(() -> {
            synchronized (mutationLock) {
                Membership membership = playerIndex.get(playerId);
                if (membership == null || membership.role() != TownRole.OWNER) {
                    return OperationResult.fail("towns.error.not-owner");
                }
                Town town = townsByInternalId.get(membership.townId());
                if (town == null) {
                    return OperationResult.fail("towns.error.no-town");
                }
                town.setStatus(TownStatus.DESTROYING);
                repository.updateTownStatus(town.internalId(), TownStatus.DESTROYING);

                List<UUID> members = new ArrayList<>(membersByTown.getOrDefault(town.internalId(), Set.of()));
                List<ChunkPos> destroyedChunks = new ArrayList<>(chunksByTown.getOrDefault(town.internalId(), Set.of()));
                removeTownAccessIndexes(town);
                publishDestroyed(town, playerId, members, destroyedChunks);
                publishReset(members, "destroy");
                publishDataPurge(town);
                dataRegistry.purgeTown(town.id(), members).join();
                repository.destroyTown(town.internalId());
                removeTownCaches(town);
                return OperationResult.ok("towns.destroy.success", UiTokens.of("town", town.name()));
            }
        });
    }


    public CompletableFuture<OperationResult> renameTown(Player player, String requestedName) {
        UUID playerId = player.getUniqueId();
        String newName = normalizeTownName(requestedName, "");
        if (newName.isBlank()) {
            return CompletableFuture.completedFuture(OperationResult.fail("towns.rename.invalid", UiTokens.of("max", String.valueOf(config.maxNameLength()))));
        }
        return api.db().async(() -> {
            synchronized (mutationLock) {
                Membership membership = playerIndex.get(playerId);
                if (membership == null) {
                    return OperationResult.fail("towns.error.no-town");
                }
                if (membership.role() != TownRole.OWNER) {
                    return OperationResult.fail("towns.error.not-owner");
                }
                Town town = townsByInternalId.get(membership.townId());
                if (town == null || town.status() != TownStatus.ACTIVE) {
                    return OperationResult.fail("towns.error.no-town");
                }
                repository.renameTown(town.internalId(), newName);
                town.setName(newName);
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new TownRenamedEvent(town, playerId, newName)));
                publish("towns.renamed", HexMessageData.builder()
                        .put("townId", town.id().toString())
                        .put("name", newName)
                        .put("byUuid", playerId.toString())
                        .build());
                return OperationResult.ok("towns.rename.success", UiTokens.of("town", newName));
            }
        });
    }

    public Optional<Town> findTown(UUID townId) {
        Long internalId = internalIdByTownUuid.get(townId);
        return internalId == null ? Optional.empty() : Optional.ofNullable(townsByInternalId.get(internalId));
    }

    public Optional<Town> townAt(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return Optional.empty();
        }
        return townAt(loc.getWorld().getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    public Optional<Town> townAt(String worldName, int chunkX, int chunkZ) {
        Integer worldId = worldIds.get(worldName);
        if (worldId == null) {
            return Optional.empty();
        }
        Long internalId = chunkIndex.get(ChunkKeys.chunkKey(worldId, chunkX, chunkZ));
        return internalId == null ? Optional.empty() : Optional.ofNullable(townsByInternalId.get(internalId));
    }

    public Optional<UUID> townIdAt(String worldName, int chunkX, int chunkZ) {
        return townAt(worldName, chunkX, chunkZ).map(Town::id);
    }

    public Optional<UUID> townIdOf(UUID playerId) {
        Membership membership = playerIndex.get(playerId);
        if (membership == null) {
            return Optional.empty();
        }
        Town town = townsByInternalId.get(membership.townId());
        return town == null ? Optional.empty() : Optional.of(town.id());
    }

    public boolean isMember(UUID playerId, UUID townId) {
        Membership membership = playerIndex.get(playerId);
        Long internalId = internalIdByTownUuid.get(townId);
        return membership != null && internalId != null && membership.townId() == internalId;
    }

    public boolean isOwner(UUID playerId, UUID townId) {
        Membership membership = playerIndex.get(playerId);
        Long internalId = internalIdByTownUuid.get(townId);
        return membership != null && internalId != null && membership.townId() == internalId && membership.role() == TownRole.OWNER;
    }

    public boolean canBuild(Player player, Location loc) {
        Optional<Town> town = townAt(loc);
        return town.isEmpty() || isMember(player.getUniqueId(), town.get().id());
    }

    public void forEachTown(Consumer<Town> visitor, int batchSize) {
        long cursor = 0L;
        while (true) {
            Page<Town> page = repository.listPage(cursor, batchSize);
            page.items().forEach(visitor);
            if (page.nextCursor() == null) {
                return;
            }
            cursor = Long.parseLong(page.nextCursor());
        }
    }

    public Page<Town> listPage(String afterTownId, int limit) {
        long cursor = 0L;
        if (afterTownId != null && !afterTownId.isBlank()) {
            try {
                cursor = Long.parseLong(afterTownId);
            } catch (NumberFormatException ignored) {
                cursor = 0L;
            }
        }
        return repository.listPage(cursor, limit);
    }

    public int countTowns() {
        return repository.countTowns();
    }

    public int growthPoints(UUID townId) {
        return findTown(townId).map(Town::growthPoints).orElse(0);
    }

    public void addGrowthPoints(UUID townId, int delta, String source) {
        findTown(townId).ifPresent(town -> {
            api.db().asyncRun(() -> {
                synchronized (mutationLock) {
                    if (!townsByInternalId.containsKey(town.internalId())) {
                        return;
                    }
                    repository.addGrowth(town.internalId(), delta);
                    town.addGrowthPoints(delta);
                    if (source != null && !source.isBlank()) {
                        MetaKey metaKey = MetaKey.parse("growth.source." + source);
                        repository.setMeta(town.internalId(), metaKey.namespace(), metaKey.key(), String.valueOf(delta));
                    }
                }
            });
        });
    }

    public String getMeta(UUID townId, String key, String def) {
        Optional<Town> town = findTown(townId);
        if (town.isEmpty()) {
            return def;
        }
        MetaKey metaKey = MetaKey.parse(key);
        return repository.getMeta(town.get().internalId(), metaKey.namespace(), metaKey.key(), def);
    }

    public int getMetaInt(UUID townId, String key, int def) {
        try {
            return Integer.parseInt(getMeta(townId, key, String.valueOf(def)));
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    public void setMeta(UUID townId, String key, String value) {
        findTown(townId).ifPresent(town -> {
            MetaKey metaKey = MetaKey.parse(key);
            api.db().asyncRun(() -> repository.setMeta(town.internalId(), metaKey.namespace(), metaKey.key(), value));
        });
    }

    public Map<String, String> getMetaPrefix(UUID townId, String keyPrefix) {
        Optional<Town> town = findTown(townId);
        if (town.isEmpty()) {
            return Map.of();
        }
        MetaKey metaKey = MetaKey.parse(keyPrefix);
        return repository.getMetaPrefix(town.get().internalId(), metaKey.namespace(), metaKey.key());
    }

    public Set<ChunkPos> chunksOf(Town town) {
        return Set.copyOf(chunksByTown.getOrDefault(town.internalId(), Set.of()));
    }

    public Set<UUID> membersOf(Town town) {
        return Set.copyOf(membersByTown.getOrDefault(town.internalId(), Set.of()));
    }

    public List<MemberInfo> memberInfos(Town town) {
        Map<UUID, TownRole> roles = new java.util.LinkedHashMap<>();

        // Starsze miasta/testowe dane potrafią nie mieć właściciela w tabeli członków.
        // Menu COOP powinno jednak zawsze pokazywać właściciela jako pierwszą główkę.
        roles.put(town.ownerId(), TownRole.OWNER);

        for (UUID playerId : membersByTown.getOrDefault(town.internalId(), Set.of())) {
            Membership membership = playerIndex.get(playerId);
            TownRole role = membership == null ? (town.ownerId().equals(playerId) ? TownRole.OWNER : TownRole.COOP) : membership.role();
            roles.merge(playerId, role, (existing, incoming) -> existing == TownRole.OWNER ? existing : incoming);
        }

        List<MemberInfo> result = new ArrayList<>();
        for (Map.Entry<UUID, TownRole> entry : roles.entrySet()) {
            UUID playerId = entry.getKey();
            result.add(new MemberInfo(playerId, safePlayerName(playerId, null), entry.getValue(), Bukkit.getPlayer(playerId) != null));
        }
        result.sort((a, b) -> {
            int roleCompare = Integer.compare(a.role() == TownRole.OWNER ? 0 : 1, b.role() == TownRole.OWNER ? 0 : 1);
            if (roleCompare != 0) return roleCompare;
            return a.name().compareToIgnoreCase(b.name());
        });
        return result;
    }

    public List<CoopRequestInfo> pendingCoopRequests(Town town, int limit) {
        return repository.listCoopRequests(town.internalId(), config.requestTtlSeconds() * 1000L, limit).stream()
                .map(record -> new CoopRequestInfo(record.requesterId(), safePlayerName(record.requesterId(), null), record.createdAt()))
                .toList();
    }

    public void registerListener(TownsListener listener) {
        listeners.add(listener);
    }

    private void indexTown(Town town, List<ChunkPos> chunks, UUID ownerId) {
        townsByInternalId.put(town.internalId(), town);
        internalIdByTownUuid.put(town.id(), town.internalId());
        membersByTown.computeIfAbsent(town.internalId(), ignored -> ConcurrentHashMap.newKeySet()).add(ownerId);
        playerIndex.put(ownerId, new Membership(town.internalId(), TownRole.OWNER));
        for (ChunkPos chunk : chunks) {
            addChunkIndex(town, chunk);
        }
    }

    private void addChunkIndex(Town town, ChunkPos chunk) {
        chunkIndex.put(ChunkKeys.chunkKey(town.worldId(), chunk.x(), chunk.z()), town.internalId());
        chunksByTown.computeIfAbsent(town.internalId(), ignored -> ConcurrentHashMap.newKeySet()).add(chunk);
    }

    private void removeTownAccessIndexes(Town town) {
        for (ChunkPos chunk : chunksByTown.getOrDefault(town.internalId(), Set.of())) {
            chunkIndex.remove(ChunkKeys.chunkKey(town.worldId(), chunk.x(), chunk.z()));
        }
        for (UUID member : membersByTown.getOrDefault(town.internalId(), Set.of())) {
            playerIndex.remove(member);
        }
    }

    private void removeTownCaches(Town town) {
        chunksByTown.remove(town.internalId());
        membersByTown.remove(town.internalId());
        townsByInternalId.remove(town.internalId());
        internalIdByTownUuid.remove(town.id());
    }

    private List<ChunkPos> initialChunks(int centerX, int centerZ) {
        List<ChunkPos> chunks = new ArrayList<>();
        int radius = config.initialRadius();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        return chunks;
    }

    private boolean isTooClose(int worldId, List<ChunkPos> chunks, int radius) {
        for (ChunkPos chunk : chunks) {
            for (int x = chunk.x() - radius; x <= chunk.x() + radius; x++) {
                for (int z = chunk.z() - radius; z <= chunk.z() + radius; z++) {
                    if (chunkIndex.containsKey(ChunkKeys.chunkKey(worldId, x, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean violatesBuffer(int worldId, long ownTownId, ChunkPos claim) {
        int radius = config.bufferChunks();
        for (int x = claim.x() - radius; x <= claim.x() + radius; x++) {
            for (int z = claim.z() - radius; z <= claim.z() + radius; z++) {
                Long otherTownId = chunkIndex.get(ChunkKeys.chunkKey(worldId, x, z));
                if (otherTownId != null && otherTownId != ownTownId) {
                    return true;
                }
            }
        }
        return false;
    }

    public String normalizeTownNameForInput(String requestedName) {
        return normalizeTownName(requestedName, "");
    }

    public String defaultTownNameForInput() {
        return defaultTownName();
    }

    private String normalizeTownName(String requestedName, String fallback) {
        String source = requestedName == null || requestedName.isBlank() ? fallback : requestedName;
        if (source == null) {
            return "";
        }
        String cleaned = source.strip()
                .replace("&", "")
                .replace("§", "")
                .replaceAll("\\s+", " ")
                .replaceAll("[^\\p{L}\\p{N}_\\- ]", "");
        if (cleaned.length() > config.maxNameLength()) {
            cleaned = cleaned.substring(0, config.maxNameLength()).strip();
        }
        if (cleaned.length() < 3) {
            return fallback == null ? "" : fallback;
        }
        return cleaned;
    }

    private String defaultTownName() {
        int number = Math.max(1, townsByInternalId.size() + 1);
        String template = config.defaultNameTemplate() == null || config.defaultNameTemplate().isBlank() ? "Town {number}" : config.defaultNameTemplate();
        return normalizeTownName(template.replace("{number}", String.valueOf(number)), "Town " + number);
    }

    private void publishCreated(Town town, UUID ownerId) {
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new TownCreatedEvent(town, ownerId)));
        publish("towns.created", HexMessageData.builder()
                .put("townId", town.id().toString())
                .put("ownerUuid", ownerId.toString())
                .put("world", town.world())
                .put("heartChunkX", town.heart().x())
                .put("heartChunkZ", town.heart().z())
                .build());
    }

    private void publishClaimed(Town town, ChunkPos chunk, UUID by) {
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new TownChunkClaimedEvent(town, chunk, by)));
        publish("towns.chunk.claimed", HexMessageData.builder()
                .put("townId", town.id().toString())
                .put("chunkX", chunk.x())
                .put("chunkZ", chunk.z())
                .put("byUuid", by.toString())
                .build());
    }

    private void publishCoopJoined(Town town, UUID playerId) {
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new TownCoopJoinedEvent(town, playerId)));
        publish("towns.coop.joined", HexMessageData.builder()
                .put("townId", town.id().toString())
                .put("uuid", playerId.toString())
                .build());
    }

    private void publishCoopLeft(Town town, UUID playerId, String reason) {
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new TownCoopLeftEvent(town, playerId, reason)));
        publish("towns.coop.left", HexMessageData.builder()
                .put("townId", town.id().toString())
                .put("uuid", playerId.toString())
                .put("reason", reason)
                .build());
    }

    private void publishDestroyed(Town town, UUID by, List<UUID> members, List<ChunkPos> chunks) {
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new TownDestroyedEvent(town, by, members, chunks)));
        publish("towns.destroyed", HexMessageData.builder()
                .put("townId", town.id().toString())
                .put("ownerUuid", town.ownerId().toString())
                .putStringList("members", members.stream().map(UUID::toString).toList())
                .put("reason", "destroy")
                .build());
    }

    private void publishReset(List<UUID> players, String reason) {
        publish("towns.reset.requested", HexMessageData.builder()
                .putStringList("playerUuids", players.stream().map(UUID::toString).toList())
                .put("reason", reason)
                .build());
    }

    private void clearOnlinePlayerData(UUID playerId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online == null) return;
            online.getInventory().clear();
            online.getEnderChest().clear();
            online.setLevel(0);
            online.setExp(0.0F);
            online.setTotalExperience(0);
        });
    }

    private String safePlayerName(UUID playerId, String fallback) {
        if (fallback != null && !fallback.isBlank()) return fallback;
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) return online.getName();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
        return offline.getName() == null ? playerId.toString().substring(0, 8) : offline.getName();
    }

    private void publishDataPurge(Town town) {
        publish("towns.data.purge", HexMessageData.builder()
                .put("townId", town.id().toString())
                .putStringList("namespaces", dataRegistry.namespaces())
                .build());
    }

    private void publish(String channel, HexMessageData data) {
        if (messageBus != null) {
            messageBus.publish(HexMessage.of(channel, "HexTowns", data));
        }
    }

    private record Membership(long townId, TownRole role) {
    }

    public record MemberInfo(UUID playerId, String name, TownRole role, boolean online) {
    }

    public record CoopRequestInfo(UUID playerId, String name, long createdAt) {
    }

    public record GrowthSyncResult(int scanned, int changed, boolean skipped) {
    }

    private record MetaKey(String namespace, String key) {
        static MetaKey parse(String rawKey) {
            String key = rawKey == null ? "value" : rawKey;
            int dot = key.indexOf('.');
            if (dot > 0 && dot < key.length() - 1 && dot <= 32) {
                return new MetaKey(key.substring(0, dot), key.substring(dot + 1));
            }
            return new MetaKey("towns", key);
        }
    }
}