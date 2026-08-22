package hex.towns.service;

import hex.core.api.HexApi;
import hex.core.api.messaging.HexMessage;
import hex.core.api.messaging.HexMessageBus;
import hex.core.api.messaging.HexMessageData;
import hex.core.api.region.RegionKey;
import hex.core.api.ui.UiTokens;
import hex.towns.api.Page;
import hex.towns.api.TownBoundItems;
import hex.towns.api.TownPermission;
import hex.towns.api.TownsListener;
import hex.towns.api.TownPurgeResult;
import hex.towns.api.TownPurgeContext;
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
import org.bukkit.World;
import org.bukkit.OfflinePlayer;
import org.bukkit.Chunk;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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

public final class TownsService implements Listener {
    private final Plugin plugin;
    private final HexApi api;
    private final TownRepository repository;
    private final TownDataRegistry dataRegistry;
    private volatile TownsConfig config;
    private final HexMessageBus messageBus;
    private final Object mutationLock = new Object();
    private final AtomicBoolean growthSyncRunning = new AtomicBoolean(false);
    private final AtomicBoolean cleanupRecoveryRunning = new AtomicBoolean(false);
    private volatile BukkitTask growthSyncTask;
    private volatile BukkitTask cleanupRecoveryTask;
    private volatile BukkitTask coopRequestPurgeTask;
    private final Set<UUID> cleanupInFlight = ConcurrentHashMap.newKeySet();
    private volatile TownWorldCleanupHandler worldCleanupHandler = job -> CompletableFuture.completedFuture(null);

    private final ConcurrentMap<Long, Town> townsByInternalId = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> internalIdByTownUuid = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Membership> playerIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> chunkIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Set<ChunkPos>> chunksByTown = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Set<UUID>> membersByTown = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> worldIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, String> worldNames = new ConcurrentHashMap<>();
    private final Set<TownsListener> listeners = ConcurrentHashMap.newKeySet();
    private final TownMemberLimitService memberLimitService;
    private final TownChunkLimitService chunkLimitService;
    private final TownBoundItemService townBoundItemService;
    private final TownPermissionService permissionService;

    public TownsService(Plugin plugin, HexApi api, TownRepository repository, TownDataRegistry dataRegistry, TownsConfig config) {
        this.plugin = plugin;
        this.api = api;
        this.repository = repository;
        this.dataRegistry = dataRegistry;
        this.config = config;
        this.messageBus = api.service(HexMessageBus.class).orElse(null);
        this.memberLimitService = new TownMemberLimitService(plugin, config);
        this.chunkLimitService = new TownChunkLimitService(plugin, config);
        this.townBoundItemService = new TownBoundItemService(plugin, this::isActiveTownUuid);
        this.permissionService = new TownPermissionService(repository, this::isOwner, this::isMember, this::internalIdForTown);
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
        memberLimitService.clear();
        chunkLimitService.clear();

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
        permissionService.load(state.permissions());
    }


    public void reloadConfig(TownsConfig config) {
        this.config = config;
        this.memberLimitService.reloadConfig(config);
        this.chunkLimitService.reloadConfig(config);
        startGrowthSync();
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
        OperationResult locationCheck = validateHeartPlacement(player.getLocation());
        if (!locationCheck.success()) return locationCheck;
        if (playerIndex.containsKey(player.getUniqueId())) {
            return OperationResult.fail("towns.create.already-member");
        }
        Integer worldId = worldIds.get(player.getWorld().getName());
        if (worldId != null && isHeartTooClose(worldId, player.getChunk().getX(), player.getChunk().getZ(), config.minDistanceChunks())) {
            return OperationResult.fail("towns.create.too-close", UiTokens.of("distance", String.valueOf(config.minDistanceChunks())));
        }
        return OperationResult.ok("towns.create.confirm", UiTokens.of("town", previewName));
    }

    public OperationResult validateHeartPlacement(Location location) {
        if (location == null || location.getWorld() == null || !config.isWorldAllowed(location.getWorld())) {
            return OperationResult.fail("towns.error.world-disabled");
        }
        if (isBlockedCreationRegion(location)) {
            return OperationResult.fail("towns.create.blocked-area");
        }
        String worldName = location.getWorld().getName();
        List<ChunkPos> initialChunks = initialChunks(location.getChunk().getX(), location.getChunk().getZ());
        if (initialChunks.stream().anyMatch(chunk -> config.isCreationBlocked(worldName, chunk.x(), chunk.z()))) {
            return OperationResult.fail("towns.create.blocked-area");
        }
        return OperationResult.ok("towns.ok");
    }

    private boolean isBlockedCreationRegion(Location location) {
        for (String rawKey : config.blockedCreationRegions()) {
            try {
                RegionKey key = RegionKey.parse(rawKey);
                if (api.regions().find(key).map(region -> region.contains(location)).orElse(false)) return true;
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Nieprawidłowy wpis towns.creation.blocked-regions: " + rawKey + " (oczekiwano namespace:id)");
            }
        }
        return false;
    }

    public CompletableFuture<OperationResult> createTown(Player player, String requestedName) {
        return createTownAt(player, requestedName, player.getLocation());
    }

    public CompletableFuture<OperationResult> createTownAt(Player player, String requestedName, int centerX, int centerZ) {
        Location location = player.getLocation().clone();
        location.setX((centerX << 4) + 8.0D);
        location.setZ((centerZ << 4) + 8.0D);
        return createTownAt(player, requestedName, location);
    }

    public CompletableFuture<OperationResult> createTownAt(Player player, String requestedName, Location creationLocation) {
        Location location = creationLocation == null ? null : creationLocation.clone();
        OperationResult locationCheck = validateHeartPlacement(location);
        if (!locationCheck.success()) return CompletableFuture.completedFuture(locationCheck);

        UUID ownerId = player.getUniqueId();
        World world = location.getWorld();
        String worldName = world.getName();
        int centerX = location.getChunk().getX();
        int centerZ = location.getChunk().getZ();
        String townName = normalizeTownName(requestedName, defaultTownName());

        return api.db().async(() -> {
            synchronized (mutationLock) {
                if (playerIndex.containsKey(ownerId)) {
                    return OperationResult.fail("towns.create.already-member");
                }

                int worldId = repository.getOrCreateWorldId(worldName);
                worldIds.put(worldName, worldId);
                worldNames.put(worldId, worldName);

                List<ChunkPos> initialChunks = initialChunks(centerX, centerZ);
                if (initialChunks.stream().anyMatch(chunk -> config.isCreationBlocked(worldName, chunk.x(), chunk.z()))) {
                    return OperationResult.fail("towns.create.blocked-area");
                }
                if (isHeartTooClose(worldId, centerX, centerZ, config.minDistanceChunks())) {
                    return OperationResult.fail("towns.create.too-close", UiTokens.of("distance", String.valueOf(config.minDistanceChunks())));
                }

                UUID townUuid = UUID.randomUUID();
                long internalId = UuidBytes.internalId(townUuid);
                Town town = new Town(internalId, townUuid, ownerId, townName, worldName, worldId,
                        new ChunkPos(centerX, centerZ), config.startingGrowthPoints(), Instant.now(), TownStatus.ACTIVE);
                repository.createTown(town, initialChunks, ownerId, config.bucketSize());
                repository.deleteAllCoopRequestsForRequester(ownerId);
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

        Membership initialMembership = playerIndex.get(playerId);
        if (initialMembership == null) {
            return CompletableFuture.completedFuture(OperationResult.fail("towns.error.no-town"));
        }
        Town initialTown = townsByInternalId.get(initialMembership.townId());
        if (initialTown == null || initialTown.status() != TownStatus.ACTIVE) {
            return CompletableFuture.completedFuture(OperationResult.fail("towns.error.no-town"));
        }

        return maxChunksAsync(initialTown).thenCompose(maxChunks -> api.db().async(() -> {
            synchronized (mutationLock) {
                Membership membership = playerIndex.get(playerId);
                if (membership == null) {
                    return OperationResult.fail("towns.error.no-town");
                }
                Town town = townsByInternalId.get(membership.townId());
                if (town == null || town.status() != TownStatus.ACTIVE) {
                    return OperationResult.fail("towns.error.no-town");
                }
                if (!town.id().equals(initialTown.id())) {
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
                if (townChunks.size() >= maxChunks) {
                    return OperationResult.fail("towns.claim.limit-reached", UiTokens.of("max", String.valueOf(maxChunks)));
                }
                if (townChunks.stream().noneMatch(existing -> existing.touchesSide(claim))) {
                    return OperationResult.fail("towns.claim.not-adjacent");
                }
                if (violatesBuffer(town.worldId(), town.internalId(), claim)) {
                    return OperationResult.fail("towns.claim.buffer-violation");
                }
                if (violatesShape(townChunks, claim, maxChunks)) {
                    int shapeMax = maxChunks >= config.expandedThresholdChunks()
                            ? Math.max(config.expandedMaxWidth(), config.expandedMaxHeight())
                            : Math.max(config.baseMaxWidth(), config.baseMaxHeight());
                    return OperationResult.fail("towns.claim.shape-violation", UiTokens.of("max", String.valueOf(shapeMax)));
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
        }));
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
        // Never gate the request on an external permission/storage lookup. maxMembers(...)
        // returns fresh cache, stale cache or the base limit immediately and refreshes in background.
        return api.db().async(() -> {
            synchronized (mutationLock) {
                Town activeTown = townsByInternalId.get(town.internalId());
                if (activeTown == null || activeTown.status() != TownStatus.ACTIVE || !activeTown.id().equals(town.id())) {
                    return OperationResult.fail("towns.error.no-town");
                }
                if (playerIndex.containsKey(playerId)) {
                    return OperationResult.fail("towns.coop.requester-has-town");
                }
                int maxMembers = maxMembers(activeTown);
                if (membersByTown.getOrDefault(activeTown.internalId(), Set.of()).size() >= maxMembers) {
                    return OperationResult.fail("towns.coop.full");
                }
                repository.upsertCoopRequest(activeTown.internalId(), playerId);
                Player owner = Bukkit.getPlayer(activeTown.ownerId());
                if (owner != null) {
                    String requesterName = player.getName();
                    Bukkit.getScheduler().runTask(plugin, () -> sendCoopRequestNotification(owner, playerId, requesterName));
                }
                return OperationResult.ok("towns.coop.request-created", UiTokens.of("town", activeTown.name()));
            }
        });
    }

    private void sendCoopRequestNotification(Player owner, UUID requesterId, String requesterName) {
        if (owner == null || !owner.isOnline() || requesterId == null) return;
        String safeName = requesterName == null || requesterName.isBlank() ? requesterId.toString() : requesterName;
        net.kyori.adventure.text.Component message = api.ui().render(
                "towns.coop.request-sent",
                UiTokens.of("player", safeName));
        net.kyori.adventure.text.Component button = net.kyori.adventure.text.Component
                .text("[ZOBACZ PROŚBĘ]", net.kyori.adventure.text.format.NamedTextColor.YELLOW,
                        net.kyori.adventure.text.format.TextDecoration.BOLD)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/town coopdecide " + requesterId))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        net.kyori.adventure.text.Component.text("Otwórz prośbę o dołączenie")));
        owner.sendMessage(message.append(net.kyori.adventure.text.Component.space()).append(button));
    }

    private void auditTown(Town town, UUID actor, String action, String data) {
        if (town == null) return;
        repository.audit(town.id(), actor, action, data);
    }

    public void audit(UUID townId, UUID actor, String action, String data) {
        if (action == null || action.isBlank()) return;
        api.db().asyncRun(() -> repository.audit(townId, actor, action, data == null ? "" : data));
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
        Membership initialMembership = playerIndex.get(ownerId);
        Town initialTown = initialMembership == null ? null : townsByInternalId.get(initialMembership.townId());
        if (initialMembership == null || initialMembership.role() != TownRole.OWNER || initialTown == null || initialTown.status() != TownStatus.ACTIVE) {
            return CompletableFuture.completedFuture(OperationResult.fail("towns.error.not-owner"));
        }

        return api.db().async(() -> {
            synchronized (mutationLock) {
                Membership ownerMembership = playerIndex.get(ownerId);
                if (ownerMembership == null || ownerMembership.role() != TownRole.OWNER) {
                    return OperationResult.fail("towns.error.not-owner");
                }
                Town town = townsByInternalId.get(ownerMembership.townId());
                if (town == null || town.status() != TownStatus.ACTIVE) {
                    return OperationResult.fail("towns.error.no-town");
                }
                if (requireOwnerStandingInTown && townAt(ownerWorld, ownerChunkX, ownerChunkZ).map(Town::internalId).orElse(-1L) != town.internalId()) {
                    return OperationResult.fail("towns.accept.must-stand-in-town");
                }

                int effectiveMaxMembers = maxMembers(town);
                Map<TownPermission, Boolean> permissionDefaults = permissionService.restrictedDefaults();
                TownRepository.AcceptCoopDbResult dbResult = repository.acceptCoopTransactional(
                        town.internalId(),
                        town.id(),
                        ownerId,
                        requesterId,
                        effectiveMaxMembers,
                        config.requestTtlSeconds() * 1000L,
                        permissionDefaults);

                switch (dbResult) {
                    case NO_REQUEST -> {
                        return OperationResult.fail("towns.accept.no-request");
                    }
                    case REQUESTER_HAS_TOWN -> {
                        return OperationResult.fail("towns.coop.requester-has-town");
                    }
                    case FULL -> {
                        return OperationResult.fail("towns.coop.full");
                    }
                    case TOWN_INACTIVE -> {
                        return OperationResult.fail("towns.error.no-town");
                    }
                    case INVALID -> {
                        return OperationResult.fail("towns.error.generic");
                    }
                    case SUCCESS -> {
                        // Runtime state is applied only after the DB transaction committed.
                        playerIndex.put(requesterId, new Membership(town.internalId(), TownRole.COOP));
                        membersByTown.computeIfAbsent(town.internalId(), ignored -> ConcurrentHashMap.newKeySet()).add(requesterId);
                        permissionService.installRestrictedRuntime(requesterId, permissionDefaults);
                        refreshMemberLimit(town);
                        publishCoopJoined(town, requesterId);
                        return OperationResult.ok("towns.accept.success", UiTokens.of("player", safePlayerName(requesterId, requesterName)));
                    }
                }
                return OperationResult.fail("towns.error.generic");
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
                if (town == null || town.status() != TownStatus.ACTIVE) {
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
                if (town == null || town.status() != TownStatus.ACTIVE || targetMembership == null || targetMembership.townId() != town.internalId()) {
                    return OperationResult.fail("towns.kick.not-member");
                }
                if (targetMembership.role() == TownRole.OWNER || targetId.equals(town.ownerId())) {
                    return OperationResult.fail("towns.kick.owner");
                }
                repository.purgeDepartedMemberData(town, targetId);
                repository.enqueuePendingPlayerReset(targetId, town.id(), "COOP_KICK");
                auditTown(town, ownerId, "TOWN_COOP_KICK", "member=" + targetId);
                playerIndex.remove(targetId);
                permissionService.remove(targetId);
                membersByTown.getOrDefault(town.internalId(), Set.of()).remove(targetId);
                refreshMemberLimit(town);
                publishCoopLeft(town, targetId, "KICK");
                publishReset(List.of(targetId), "coop_kick");
                clearOnlinePlayerData(targetId);
                notifyKickedMember(town, targetId);
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
                if (town != null && town.status() != TownStatus.ACTIVE) {
                    return OperationResult.fail("towns.error.no-town");
                }
                if (town != null) {
                    repository.purgeDepartedMemberData(town, playerId);
                    repository.enqueuePendingPlayerReset(playerId, town.id(), "ENDCOOP");
                    auditTown(town, playerId, "TOWN_COOP_LEAVE", "");
                } else {
                    repository.enqueuePendingPlayerReset(playerId, null, "ENDCOOP");
                    repository.removeMember(playerId);
                    repository.deleteAllCoopRequestsForRequester(playerId);
                }
                playerIndex.remove(playerId);
                permissionService.remove(playerId);
                membersByTown.getOrDefault(membership.townId(), Set.of()).remove(playerId);
                if (town != null) {
                    refreshMemberLimit(town);
                    publishCoopLeft(town, playerId, "RESIGN");
                    publishReset(List.of(playerId), "endcoop");
                } else {
                    publishReset(List.of(playerId), "endcoop");
                }
                clearOnlinePlayerData(playerId);
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
                    return DestroyPreparation.failure(OperationResult.fail("towns.error.not-owner"));
                }
                Town town = townsByInternalId.get(membership.townId());
                if (town == null || town.status() != TownStatus.ACTIVE) {
                    return DestroyPreparation.failure(OperationResult.fail("towns.error.no-town"));
                }

                List<UUID> members = new ArrayList<>(membersByTown.getOrDefault(town.internalId(), Set.of()));
                if (!members.contains(town.ownerId())) members.add(town.ownerId());
                List<ChunkPos> chunks = new ArrayList<>(chunksByTown.getOrDefault(town.internalId(), Set.of()));

                // The status change and the durable cleanup snapshots are one transaction.
                TownRepository.CleanupJob job = repository.beginDestroyJob(town, chunks, members, requiredCleanupNamespaces());
                auditTown(town, playerId, "TOWN_DESTROY_START", "members=" + members.size() + ",chunks=" + chunks.size());
                town.setStatus(TownStatus.DESTROYING);

                // Keep claim/member indexes in memory while DESTROYING. Protection uses the
                // status to lock the town read-only until every cleanup part is complete.
                return DestroyPreparation.success(job, List.copyOf(members), List.copyOf(chunks));
            }
        }).thenCompose(preparation -> {
            if (preparation.failure() != null) return CompletableFuture.completedFuture(preparation.failure());

            TownRepository.CleanupJob job = preparation.job();
            Town town = job.town();
            List<UUID> coopMembers = preparation.members().stream().filter(member -> !member.equals(town.ownerId())).toList();
            notifyTownDestroyedMembers(town, coopMembers);
            publishDestroyed(town, playerId, preparation.members(), preparation.chunks());
            publishReset(preparation.members(), "destroy");
            clearOnlinePlayersData(preparation.members());
            publishDataPurge(town);

            long startedAt = System.currentTimeMillis();
            plugin.getLogger().info("[TownCleanup] START town=" + town.id() + " chunks=" + preparation.chunks().size() + " members=" + preparation.members().size());
            return executeCleanupJob(job).thenApply(complete -> {
                if (complete) {
                    plugin.getLogger().info("[TownCleanup] DONE town=" + town.id() + " durationMs=" + (System.currentTimeMillis() - startedAt));
                } else {
                    plugin.getLogger().warning("[TownCleanup] town=" + town.id() + " has pending retryable cleanup parts; claims remain LOCKED until retry succeeds.");
                }
                return OperationResult.ok("towns.destroy.success", UiTokens.of("town", town.name()));
            });
        }).exceptionally(error -> {
            plugin.getLogger().severe("Town destroy failed: " + rootMessage(error));
            return OperationResult.fail("towns.error.db", UiTokens.of("error", rootMessage(error)));
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
                long now = System.currentTimeMillis();
                long cooldownMillis = 48L * 60L * 60L * 1000L;
                long lastRenameAt = parseLong(repository.getMeta(town.internalId(), "towns", "rename.last_at", "0"), 0L);
                if (lastRenameAt > 0L && now - lastRenameAt < cooldownMillis) {
                    long remainingMillis = cooldownMillis - (now - lastRenameAt);
                    long hours = Math.max(1L, (remainingMillis + 3_599_999L) / 3_600_000L);
                    return OperationResult.fail("towns.rename.cooldown", UiTokens.of("hours", String.valueOf(hours)));
                }
                repository.renameTown(town.internalId(), newName);
                repository.setMeta(town.internalId(), "towns", "rename.last_at", String.valueOf(now));
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
        if (internalId == null) return Optional.empty();
        Town town = townsByInternalId.get(internalId);
        return town != null && town.status() == TownStatus.ACTIVE ? Optional.of(town) : Optional.empty();
    }

    /**
     * Internal/admin lookup that deliberately keeps DESTROYING towns visible.
     * Heart reconciliation needs this so it never mistakes an in-flight destroy
     * for an orphan merely because public gameplay lookups expose ACTIVE only.
     */
    public Optional<Town> findTownIncludingDestroying(UUID townId) {
        if (townId == null) return Optional.empty();
        Long internalId = internalIdByTownUuid.get(townId);
        if (internalId == null) return Optional.empty();
        return Optional.ofNullable(townsByInternalId.get(internalId));
    }

    public Optional<Town> findTownByInternalId(long internalId) {
        Town town = townsByInternalId.get(internalId);
        return town != null && town.status() == TownStatus.ACTIVE ? Optional.of(town) : Optional.empty();
    }

    public List<Town> findActiveTownsByExactName(String name) {
        if (name == null || name.isBlank()) return List.of();
        return townsByInternalId.values().stream()
                .filter(town -> town.status() == TownStatus.ACTIVE)
                .filter(town -> town.name().equalsIgnoreCase(name))
                .sorted(java.util.Comparator.comparingLong(Town::internalId))
                .toList();
    }

    public List<Town> searchActiveTowns(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        int capped = Math.max(1, Math.min(50, limit));
        return townsByInternalId.values().stream()
                .filter(town -> town.status() == TownStatus.ACTIVE)
                .filter(town -> town.name().toLowerCase(java.util.Locale.ROOT).contains(needle))
                .sorted(java.util.Comparator
                        .comparing((Town town) -> !town.name().equalsIgnoreCase(query))
                        .thenComparingLong(Town::internalId))
                .limit(capped)
                .toList();
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
        if (internalId == null) return Optional.empty();
        Town town = townsByInternalId.get(internalId);
        return town != null && town.status() == TownStatus.ACTIVE ? Optional.of(town) : Optional.empty();
    }

    /** Claim lookup used by protection. DESTROYING towns stay locked until cleanup is fully complete. */
    public Optional<Town> protectedTownAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return Optional.empty();
        return protectedTownAt(loc.getWorld().getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    public Optional<Town> protectedTownAt(String worldName, int chunkX, int chunkZ) {
        Integer worldId = worldIds.get(worldName);
        if (worldId == null) return Optional.empty();
        Long internalId = chunkIndex.get(ChunkKeys.chunkKey(worldId, chunkX, chunkZ));
        if (internalId == null) return Optional.empty();
        Town town = townsByInternalId.get(internalId);
        return town == null ? Optional.empty() : Optional.of(town);
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
        return town == null || town.status() != TownStatus.ACTIVE ? Optional.empty() : Optional.of(town.id());
    }

    public boolean isMember(UUID playerId, UUID townId) {
        Membership membership = playerIndex.get(playerId);
        Long internalId = internalIdByTownUuid.get(townId);
        Town town = internalId == null ? null : townsByInternalId.get(internalId);
        return membership != null && town != null && town.status() == TownStatus.ACTIVE && membership.townId() == internalId;
    }

    public boolean isOwner(UUID playerId, UUID townId) {
        Membership membership = playerIndex.get(playerId);
        Long internalId = internalIdByTownUuid.get(townId);
        Town town = internalId == null ? null : townsByInternalId.get(internalId);
        return membership != null && town != null && town.status() == TownStatus.ACTIVE && membership.townId() == internalId && membership.role() == TownRole.OWNER;
    }

    /**
     * Access bypass for online administrators. It never mutates playerIndex and therefore never
     * changes townIdOf/isMember/member counts, progression ownership or outsider GUI routing.
     */
    public boolean hasAdminBypass(UUID playerId) {
        if (playerId == null) return false;
        Player player = Bukkit.getPlayer(playerId);
        return player != null && player.isOnline() && player.hasPermission("hextowns.admin.bypass");
    }

    public boolean canActAsMember(UUID playerId, UUID townId) {
        return townId != null && isActiveTownUuid(townId) && (isMember(playerId, townId) || hasAdminBypass(playerId));
    }

    public boolean canBuild(Player player, Location loc) {
        if (player == null || loc == null || loc.getWorld() == null) return false;
        return blockingTownForBuild(player.getUniqueId(), loc).isEmpty();
    }

    public Optional<Town> blockingTownForBuild(UUID playerId, Location loc) {
        if (loc == null || loc.getWorld() == null) return Optional.empty();
        Integer worldId = worldIds.get(loc.getWorld().getName());
        if (worldId == null) return Optional.empty();

        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        int radius = config.outsiderBuildBufferChunks();

        // Direct claim: destroying is read-only for everybody. Active COOP additionally
        // respects the granular BUILD permission.
        Optional<Town> direct = protectedTownAt(loc);
        if (direct.isPresent()) {
            Town town = direct.get();
            if (town.status() != TownStatus.ACTIVE) return direct;
            if (!can(playerId, town.id(), TownPermission.BUILD)) return direct;
            return Optional.empty();
        }

        if (radius <= 0) return Optional.empty();
        Set<Long> checked = new HashSet<>();
        for (int x = chunkX - radius; x <= chunkX + radius; x++) {
            for (int z = chunkZ - radius; z <= chunkZ + radius; z++) {
                Long internalId = chunkIndex.get(ChunkKeys.chunkKey(worldId, x, z));
                if (internalId == null || !checked.add(internalId)) continue;
                Town town = townsByInternalId.get(internalId);
                if (town == null) continue;
                if (town.status() != TownStatus.ACTIVE || !canActAsMember(playerId, town.id())) return Optional.of(town);
            }
        }
        return Optional.empty();
    }

    public boolean isPvpAllowed(Location loc) {
        return townAt(loc).isEmpty() || config.protectionPvp();
    }

    public TownBoundItems townBoundItems() {
        return townBoundItemService;
    }

    public boolean can(UUID playerId, UUID townId, TownPermission permission) {
        if (playerId == null || townId == null || permission == null || !isActiveTownUuid(townId)) return false;
        if (hasAdminBypass(playerId)) return true;
        return permissionService.can(playerId, townId, permission);
    }

    public Map<TownPermission, Boolean> permissionsOf(UUID playerId, UUID townId) {
        java.util.EnumMap<TownPermission, Boolean> result = new java.util.EnumMap<>(TownPermission.class);
        for (TownPermission permission : TownPermission.values()) result.put(permission, can(playerId, townId, permission));
        return Map.copyOf(result);
    }

    public CompletableFuture<Boolean> setPermission(UUID ownerId, UUID townId, UUID memberId, TownPermission permission, boolean allowed) {
        return api.db().async(() -> {
            boolean changed = permissionService.set(ownerId, townId, memberId, permission, allowed);
            if (changed) findTown(townId).ifPresent(town -> auditTown(town, ownerId, "TOWN_PERMISSION_CHANGE",
                    "member=" + memberId + ",permission=" + permission + ",allowed=" + allowed));
            return changed;
        });
    }

    private boolean isActiveTownUuid(UUID townUuid) {
        Long internalId = internalIdByTownUuid.get(townUuid);
        Town town = internalId == null ? null : townsByInternalId.get(internalId);
        return town != null && town.status() == TownStatus.ACTIVE;
    }

    private Long internalIdForTown(UUID townUuid) {
        return internalIdByTownUuid.get(townUuid);
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

    public int maxMembers(Town town) {
        if (town == null) return config.maxMembers();
        return memberLimitService.cachedOrBase(town, memberIdsForLimit(town));
    }

    public CompletableFuture<Integer> maxMembersAsync(Town town) {
        if (town == null) return CompletableFuture.completedFuture(config.maxMembers());
        return memberLimitService.resolveAsync(town, memberIdsForLimit(town));
    }

    public String playerRankDisplay(UUID playerId) {
        return memberLimitService.rankDisplay(playerId);
    }

    public CompletableFuture<CoopDebugInfo> coopDebug(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(new CoopDebugInfo(null, null, null, List.of(), 0, null));
        Membership runtimeMembership = playerIndex.get(playerId);
        return api.db().async(() -> {
            TownRepository.MemberRecord dbMember = repository.findMemberRecord(playerId).orElse(null);
            List<CoopDebugRequest> requests = repository.listCoopRequestsForRequester(playerId, 100).stream()
                    .map(row -> {
                        Town target = townsByInternalId.get(row.townId());
                        return new CoopDebugRequest(row.townId(), target == null ? "<missing-runtime-town>" : target.name(), row.createdAt(),
                                Math.max(0L, System.currentTimeMillis() - row.createdAt()));
                    }).toList();
            Town runtimeTown = runtimeMembership == null ? null : townsByInternalId.get(runtimeMembership.townId());
            Town dbTown = dbMember == null ? null : townsByInternalId.get(dbMember.townId());
            Town limitTown = runtimeTown;
            if (limitTown == null && !requests.isEmpty()) limitTown = townsByInternalId.get(requests.get(0).townId());
            TownMemberLimitService.DebugInfo limit = limitTown == null ? null : memberLimitService.debug(limitTown);
            int memberCount = limitTown == null ? 0 : membersByTown.getOrDefault(limitTown.internalId(), Set.of()).size();
            return new CoopDebugInfo(
                    playerId,
                    runtimeTown == null ? null : runtimeTown.name() + "#" + runtimeTown.internalId() + ":" + runtimeMembership.role(),
                    dbMember == null ? null : (dbTown == null ? "townId=" + dbMember.townId() : dbTown.name() + "#" + dbMember.townId()) + ":" + dbMember.role(),
                    requests,
                    memberCount,
                    limit);
        });
    }

    public int maxChunks(Town town) {
        if (town == null) return config.maxChunks();
        return chunkLimitService.cachedOrBase(town, memberIdsForLimit(town));
    }

    public CompletableFuture<Integer> maxChunksAsync(Town town) {
        if (town == null) return CompletableFuture.completedFuture(config.maxChunks());
        return chunkLimitService.resolveAsync(town, memberIdsForLimit(town));
    }

    private void refreshMemberLimit(Town town) {
        if (town == null) return;
        memberLimitService.invalidate(town.id());
        chunkLimitService.invalidate(town.id());
        Set<UUID> members = memberIdsForLimit(town);
        memberLimitService.resolveAsync(town, members);
        chunkLimitService.resolveAsync(town, members);
    }

    private Set<UUID> memberIdsForLimit(Town town) {
        Set<UUID> members = new HashSet<>(membersByTown.getOrDefault(town.internalId(), Set.of()));
        members.add(town.ownerId());
        return Set.copyOf(members);
    }

    public List<CoopRequestInfo> pendingCoopRequests(Town town, int limit) {
        return repository.listCoopRequests(town.internalId(), config.requestTtlSeconds() * 1000L, limit).stream()
                .map(record -> new CoopRequestInfo(record.requesterId(), safePlayerName(record.requesterId(), null), record.createdAt()))
                .toList();
    }

    /** Resolve a pending request by UUID or display name without performing DB I/O on the command thread. */
    public CompletableFuture<Optional<CoopRequestInfo>> pendingCoopRequestAsync(Player owner, String rawPlayer) {
        if (owner == null || rawPlayer == null || rawPlayer.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Membership membership = playerIndex.get(owner.getUniqueId());
        if (membership == null || membership.role() != TownRole.OWNER) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Town town = townsByInternalId.get(membership.townId());
        if (town == null || town.status() != TownStatus.ACTIVE) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        UUID requestedUuid = null;
        try { requestedUuid = UUID.fromString(rawPlayer.trim()); } catch (IllegalArgumentException ignored) { }
        final UUID uuid = requestedUuid;
        final String requestedName = rawPlayer.trim();
        return api.db().async(() -> repository.listCoopRequests(town.internalId(), config.requestTtlSeconds() * 1000L, 100).stream()
                .map(record -> new CoopRequestInfo(record.requesterId(), safePlayerName(record.requesterId(), null), record.createdAt()))
                .filter(request -> (uuid != null && uuid.equals(request.playerId())) || request.name().equalsIgnoreCase(requestedName))
                .findFirst());
    }


    private static long parseLong(String raw, long def) {
        try {
            return Long.parseLong(raw == null ? "" : raw.trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
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
            permissionService.remove(member);
        }
    }

    private void removeTownCaches(Town town) {
        memberLimitService.invalidate(town.id());
        chunkLimitService.invalidate(town.id());
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

    private boolean isHeartTooClose(int worldId, int heartX, int heartZ, int minDistance) {
        if (minDistance <= 0) return false;
        for (Town other : townsByInternalId.values()) {
            if (other == null || other.status() != TownStatus.ACTIVE || other.worldId() != worldId) continue;
            int distance = Math.max(Math.abs(other.heart().x() - heartX), Math.abs(other.heart().z() - heartZ));
            if (distance < minDistance) return true;
        }
        return false;
    }

    private boolean violatesShape(Set<ChunkPos> existing, ChunkPos claim, int effectiveMaxChunks) {
        if (!config.townShapeEnabled()) return false;
        int minX = claim.x();
        int maxX = claim.x();
        int minZ = claim.z();
        int maxZ = claim.z();
        for (ChunkPos chunk : existing) {
            minX = Math.min(minX, chunk.x());
            maxX = Math.max(maxX, chunk.x());
            minZ = Math.min(minZ, chunk.z());
            maxZ = Math.max(maxZ, chunk.z());
        }
        int width = maxX - minX + 1;
        int height = maxZ - minZ + 1;
        boolean expanded = effectiveMaxChunks >= config.expandedThresholdChunks();
        int allowedWidth = expanded ? config.expandedMaxWidth() : config.baseMaxWidth();
        int allowedHeight = expanded ? config.expandedMaxHeight() : config.baseMaxHeight();
        return width > allowedWidth || height > allowedHeight;
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

    private void notifyTownDestroyedMembers(Town town, List<UUID> coopMembers) {
        if (coopMembers == null || coopMembers.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (UUID memberId : coopMembers) {
                Player online = Bukkit.getPlayer(memberId);
                if (online != null) {
                    api.ui().send(online, "towns.destroy.member-removed", UiTokens.of("town", town.name()));
                }
            }
        });
    }

    private void notifyKickedMember(Town town, UUID playerId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online != null) {
                online.closeInventory();
                api.ui().send(online, "towns.kick.target", UiTokens.of("town", town.name()));
            }
        });
    }

    private void clearOnlinePlayersData(List<UUID> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) return;
        for (UUID playerId : playerIds) clearOnlinePlayerData(playerId);
    }

    private void clearOnlinePlayerData(UUID playerId) {
        if (playerId == null) return;
        api.db().async(() -> repository.pendingPlayerReset(playerId)).thenAccept(optional -> optional.ifPresent(pending ->
                Bukkit.getScheduler().runTask(plugin, () -> clearOnlinePlayerDataNow(pending)))).exceptionally(error -> {
            plugin.getLogger().warning("Could not load pending player reset for " + playerId + ": " + rootMessage(error));
            return null;
        });
    }

    private void clearOnlinePlayerDataNow(TownRepository.PendingPlayerReset pending) {
        if (pending == null) return;
        UUID playerId = pending.playerUuid();
        Player online = Bukkit.getPlayer(playerId);
        if (online == null) return; // durable pending row remains until the player joins.
        try {
            online.closeInventory();
            PlayerResetMode mode = resetModeForReason(pending.reason());
            if (mode == PlayerResetMode.FULL) {
                online.getInventory().clear();
                online.getEnderChest().clear();
                online.setLevel(0);
                online.setExp(0.0F);
                online.setTotalExperience(0);
            } else if (mode == PlayerResetMode.TOWN_BOUND_ONLY && pending.townUuid() != null) {
                int removed = townBoundItemService.purgeTownBound(online.getInventory(), pending.townUuid());
                removed += townBoundItemService.purgeTownBound(online.getEnderChest(), pending.townUuid());
                if (removed > 0) audit(pending.townUuid(), playerId, "TOWN_BOUND_PURGED", "player-reset count=" + removed);
            }
            online.saveData();
            api.db().asyncRun(() -> repository.completePendingPlayerReset(playerId));
        } catch (Throwable failure) {
            api.db().asyncRun(() -> repository.failPendingPlayerReset(playerId, rootMessage(failure)));
            plugin.getLogger().warning("Persistent player reset failed for " + playerId + ": " + rootMessage(failure));
        }
    }

    private PlayerResetMode resetModeForReason(String reason) {
        if (reason == null) return config.destroyResetMode();
        String normalized = reason.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("KICK")) return config.kickResetMode();
        if (normalized.contains("ENDCOOP") || normalized.contains("LEAVE") || normalized.contains("RESIGN")) return config.leaveResetMode();
        if (normalized.contains("DESTROY")) return config.destroyResetMode();
        return config.destroyResetMode();
    }

    @EventHandler
    public void onPendingResetJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();

        Membership membership = playerIndex.get(playerId);
        if (membership != null) {
            Town town = townsByInternalId.get(membership.townId());
            if (town != null && town.status() == TownStatus.ACTIVE) refreshMemberLimit(town);
        }

        api.db().async(() -> repository.pendingPlayerReset(playerId)).thenAccept(optional -> optional.ifPresent(pending ->
                Bukkit.getScheduler().runTask(plugin, () -> clearOnlinePlayerDataNow(pending)))).exceptionally(error -> {
            plugin.getLogger().warning("Could not read pending player reset for " + playerId + ": " + rootMessage(error));
            return null;
        });
    }

    public void setWorldCleanupHandler(TownWorldCleanupHandler handler) {
        this.worldCleanupHandler = handler == null ? job -> CompletableFuture.completedFuture(null) : handler;
    }

    public void startLifecycleMaintenance() {
        stopLifecycleMaintenance();
        long retryTicks = Math.max(20L, config.cleanupRetryIntervalTicks());
        cleanupRecoveryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::resumePendingCleanupJobs, retryTicks, retryTicks);
        long coopTicks = Math.max(20L, config.coopRequestPurgeIntervalSeconds() * 20L);
        coopRequestPurgeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                int deleted = repository.purgeExpiredCoopRequests(config.requestTtlSeconds() * 1000L);
                if (deleted > 0) plugin.getLogger().info("Purged " + deleted + " expired COOP requests.");
            } catch (Throwable error) {
                plugin.getLogger().warning("COOP request purge failed: " + rootMessage(error));
            }
        }, coopTicks, coopTicks);
        resumePendingCleanupJobs();
    }

    public void stopLifecycleMaintenance() {
        if (cleanupRecoveryTask != null) cleanupRecoveryTask.cancel();
        if (coopRequestPurgeTask != null) coopRequestPurgeTask.cancel();
        cleanupRecoveryTask = null;
        coopRequestPurgeTask = null;
    }

    public void recoverDestroyingJobs() {
        api.db().asyncRun(() -> repository.recoverDestroyingJobs(requiredCleanupNamespaces()))
                .thenRun(this::resumePendingCleanupJobs)
                .exceptionally(error -> {
                    plugin.getLogger().severe("Destroy recovery bootstrap failed: " + rootMessage(error));
                    return null;
                });
    }

    public void resumePendingCleanupJobs() {
        if (!cleanupRecoveryRunning.compareAndSet(false, true)) return;
        api.db().async(() -> {
            repository.recoverDestroyingJobs(requiredCleanupNamespaces());
            return repository.loadPendingCleanupJobs();
        }).thenCompose(jobs -> {
            CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
            for (TownRepository.CleanupJob job : jobs) {
                chain = chain.thenCompose(ignored -> executeCleanupJob(job));
            }
            return chain;
        }).whenComplete((ignored, error) -> {
            cleanupRecoveryRunning.set(false);
            if (error != null) plugin.getLogger().warning("Cleanup recovery pass failed: " + rootMessage(error));
        });
    }

    public CompletableFuture<Boolean> retryCleanup(UUID townUuid) {
        return api.db().async(() -> repository.loadCleanupJob(townUuid))
                .thenCompose(job -> job.map(this::executeCleanupJob).orElseGet(() -> CompletableFuture.completedFuture(false)));
    }

    /** Retry one registered dependent subsystem without touching unrelated namespace parts. */
    public CompletableFuture<Boolean> retryCleanupNamespace(UUID townUuid, String namespace) {
        if (townUuid == null || namespace == null || namespace.isBlank()) return CompletableFuture.completedFuture(false);
        String normalized = namespace.toLowerCase(java.util.Locale.ROOT);
        if (!dataRegistry.isRegistered(normalized)) return CompletableFuture.completedFuture(false);
        return api.db().async(() -> {
            repository.ensureCleanupParts(townUuid, List.of(normalized));
            return repository.loadCleanupJob(townUuid);
        }).thenCompose(optional -> {
            if (optional.isEmpty()) return CompletableFuture.completedFuture(false);
            TownRepository.CleanupJob job = optional.get();
            TownPurgeContext context = purgeContext(job);
            return dataRegistry.purgeNamespace(normalized, context).thenCompose(result ->
                    api.db().async(() -> {
                        repository.markCleanupPart(townUuid, "NS:" + normalized, result.success(), result.error());
                        if (!result.success()) repository.noteCleanupRetry(townUuid, result.error());
                        return result.success();
                    }));
        });
    }

    public CompletableFuture<Void> rollbackNewTown(UUID townUuid) {
        if (townUuid == null) return CompletableFuture.completedFuture(null);
        return api.db().asyncRun(() -> {
            synchronized (mutationLock) {
                Long internalId = internalIdByTownUuid.get(townUuid);
                Town town = internalId == null ? null : townsByInternalId.get(internalId);
                if (town == null) return;
                repository.destroyTownCore(town);
                removeTownAccessIndexes(town);
                removeTownCaches(town);
            }
        });
    }

    public CompletableFuture<Integer> pendingPlayerResetCount() {
        return api.db().async(repository::pendingPlayerResetCount);
    }

    public CompletableFuture<List<TownRepository.CleanupJobSummary>> cleanupJobSummaries(int limit) {
        return api.db().async(() -> repository.cleanupJobSummaries(limit));
    }

    public CompletableFuture<List<TownRepository.NamespaceRegistration>> cleanupNamespaces() {
        return api.db().async(repository::namespaceRegistrations);
    }

    public CompletableFuture<Boolean> setCleanupNamespaceActive(String namespace, boolean active) {
        if (namespace == null || namespace.isBlank()) return CompletableFuture.completedFuture(false);
        String normalized = namespace.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (normalized.isBlank()) return CompletableFuture.completedFuture(false);
        return api.db().async(() -> repository.setNamespaceActive(normalized, active));
    }

    public CompletableFuture<TownRepository.OrphanScanReport> scanOrphans() {
        Set<String> runtimeNamespaces = Set.copyOf(dataRegistry.namespaces());
        return api.db().async(() -> repository.scanOrphans(runtimeNamespaces));
    }

    public CompletableFuture<TownRepository.OrphanRepairReport> repairSafeOrphans(boolean apply) {
        return api.db().async(() -> repository.repairSafeOrphans(apply));
    }

    /** Recovery/resume is deliberately separate from the read-only orphan scanner. */
    public void resumeCleanupRecovery() {
        recoverDestroyingJobs();
        resumePendingCleanupJobs();
    }

    private List<String> requiredCleanupNamespaces() {
        // New cleanup jobs snapshot only handlers that are actually active in this JVM boot.
        // town_data_namespaces is historical/audit metadata and must never make a removed plugin
        // block every future town deletion.
        Set<String> result = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        result.addAll(dataRegistry.namespaces());
        return List.copyOf(result);
    }

    private CompletableFuture<Boolean> executeCleanupJob(TownRepository.CleanupJob staleJob) {
        UUID townUuid = staleJob.town().id();
        if (!cleanupInFlight.add(townUuid)) return CompletableFuture.completedFuture(false);

        return api.db().async(() -> {
            repository.ensureCleanupParts(townUuid, null);
            return repository.loadCleanupJob(townUuid).orElse(staleJob);
        }).thenCompose(job -> purgeCleanupNamespaces(job)
                .thenCompose(ignored -> cleanupBoundStorage(job))
                .thenCompose(ignored -> cleanupWorld(job))
                .thenCompose(ignored -> deleteCleanupCore(job))
                .thenCompose(ignored -> api.db().async(() -> repository.markCleanupDoneIfComplete(townUuid)))
                .thenApply(done -> {
                    if (done) {
                        repository.audit(townUuid, job.town().ownerId(), "TOWN_DESTROY_DONE", "cleanup complete");
                        synchronized (mutationLock) {
                            removeTownAccessIndexes(job.town());
                            removeTownCaches(job.town());
                        }
                    }
                    return done;
                }))
                .whenComplete((ignored, error) -> cleanupInFlight.remove(townUuid));
    }

    private CompletableFuture<Void> purgeCleanupNamespaces(TownRepository.CleanupJob job) {
        List<CompletableFuture<TownPurgeResult>> purges = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        List<String> retired = new ArrayList<>();
        for (Map.Entry<String, TownRepository.CleanupPart> entry : job.parts().entrySet()) {
            if (!entry.getKey().startsWith("NS:") || "DONE".equals(entry.getValue().state())) continue;
            String namespace = entry.getKey().substring(3);
            if (!dataRegistry.isRegistered(namespace)) {
                // A namespace explicitly retired by an admin is no longer a required dependency.
                // This is primarily for old jobs created by the historical-registry bug.
                if (repository.isNamespaceRetired(namespace)) {
                    retired.add(namespace);
                    continue;
                }
                unavailable.add(namespace);
                continue;
            }
            purges.add(dataRegistry.purgeNamespace(namespace, purgeContext(job)));
        }

        CompletableFuture<Void> retiredMarks = retired.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : api.db().asyncRun(() -> {
                    for (String namespace : retired) {
                        repository.markCleanupPart(job.town().id(), "NS:" + namespace, true, "retired namespace skipped");
                        plugin.getLogger().warning("[TownCleanup] skipped retired namespace=" + namespace + " town=" + job.town().id());
                    }
                });

        if (!unavailable.isEmpty()) {
            String message = "Cleanup namespace unavailable: " + String.join(",", unavailable);
            return retiredMarks.thenCompose(ignored -> api.db().asyncRun(() -> {
                for (String namespace : unavailable) {
                    repository.markCleanupPart(job.town().id(), "NS:" + namespace, false, message);
                }
                repository.noteCleanupRetry(job.town().id(), message);
            })).thenCompose(ignored -> CompletableFuture.<Void>failedFuture(new IllegalStateException(message)));
        }

        if (purges.isEmpty()) return retiredMarks;
        return retiredMarks.thenCompose(v -> CompletableFuture.allOf(purges.toArray(CompletableFuture[]::new))).thenCompose(ignored -> {
            List<TownPurgeResult> results = purges.stream().map(CompletableFuture::join).toList();
            return api.db().asyncRun(() -> {
                for (TownPurgeResult result : results) {
                    String part = "NS:" + result.namespace();
                    repository.markCleanupPart(job.town().id(), part, result.success(), result.error());
                    if (result.success()) plugin.getLogger().info("[TownCleanup] " + result.namespace().toUpperCase(java.util.Locale.ROOT) + " ok town=" + job.town().id());
                    else {
                        repository.noteCleanupRetry(job.town().id(), result.error());
                        plugin.getLogger().warning("[TownCleanup] " + result.namespace().toUpperCase(java.util.Locale.ROOT) + " failed town=" + job.town().id() + " error=" + result.error());
                    }
                }
            }).thenCompose(done -> {
                String failures = results.stream().filter(r -> !r.success()).map(r -> r.namespace() + ":" + r.error()).collect(java.util.stream.Collectors.joining("; "));
                return failures.isBlank() ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.<Void>failedFuture(new IllegalStateException("Dependent cleanup failed: " + failures));
            });
        });
    }

    private TownPurgeContext purgeContext(TownRepository.CleanupJob job) {
        return new TownPurgeContext(
                job.town().id(),
                job.town().internalId(),
                job.town().ownerId(),
                job.town().world(),
                Set.copyOf(job.chunks()),
                job.members());
    }

    private CompletableFuture<Void> cleanupBoundStorage(TownRepository.CleanupJob job) {
        TownRepository.CleanupPart part = job.parts().get("BOUND_STORAGE");
        if (part != null && "DONE".equals(part.state())) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> result = new CompletableFuture<>();
        List<ChunkPos> chunks = job.chunks();
        int initialCursor = Math.max(0, Math.min(job.boundScanCursor(), chunks.size()));

        class Step implements Runnable {
            int cursor = initialCursor;

            @Override public void run() {
                if (cursor >= chunks.size()) {
                    api.db().asyncRun(() -> {
                        repository.resetBoundScanCursor(job.town().id());
                        repository.markCleanupPart(job.town().id(), "BOUND_STORAGE", true, null);
                        repository.markCleanupState(job.town().id(), "BOUND_STORAGE_DONE", null);
                    }).whenComplete((ignored, error) -> {
                        if (error == null) result.complete(null);
                        else result.completeExceptionally(error);
                    });
                    return;
                }
                ChunkPos pos = chunks.get(cursor);
                try {
                    World world = Bukkit.getWorld(job.town().world());
                    if (world == null) throw new IllegalStateException("World is not loaded: " + job.town().world());
                    Chunk chunk = world.getChunkAt(pos.x(), pos.z());
                    chunk.load(true);
                    int purged = 0;
                    for (BlockState state : chunk.getTileEntities()) {
                        if (state instanceof InventoryHolder holder) purged += townBoundItemService.purgeTownBound(holder.getInventory(), job.town().id());
                    }
                    for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
                        if (entity instanceof InventoryHolder holder) purged += townBoundItemService.purgeTownBound(holder.getInventory(), job.town().id());
                        if (entity instanceof Item item && townBoundItemService.originTown(item.getItemStack()).filter(job.town().id()::equals).isPresent()) {
                            purged += Math.max(1, item.getItemStack().getAmount());
                            item.remove();
                        }
                    }
                    if (purged > 0) plugin.getLogger().info("[TownCleanup] BOUND_STORAGE purged=" + purged + " town=" + job.town().id() + " chunk=" + pos.x() + "," + pos.z());
                    cursor++;
                    int persistedCursor = cursor;
                    int purgedCount = purged;
                    api.db().asyncRun(() -> {
                        repository.updateBoundScanCursor(job.town().id(), persistedCursor);
                        if (purgedCount > 0) repository.audit(job.town().id(), null, "TOWN_BOUND_PURGED", "count=" + purgedCount + ",chunk=" + pos.x() + "," + pos.z());
                    }).whenComplete((ignored, error) -> {
                        if (error != null) {
                            String message = rootMessage(error);
                            api.db().asyncRun(() -> {
                                repository.markCleanupPart(job.town().id(), "BOUND_STORAGE", false, message);
                                repository.noteCleanupRetry(job.town().id(), message);
                            });
                            result.completeExceptionally(error);
                        } else if (!result.isDone()) {
                            Bukkit.getScheduler().runTask(plugin, this);
                        }
                    });
                } catch (Throwable error) {
                    String message = rootMessage(error);
                    api.db().asyncRun(() -> {
                        repository.markCleanupPart(job.town().id(), "BOUND_STORAGE", false, message);
                        repository.noteCleanupRetry(job.town().id(), message);
                    });
                    result.completeExceptionally(error);
                }
            }
        }

        Bukkit.getScheduler().runTask(plugin, new Step());
        return result;
    }

    private CompletableFuture<Void> deleteCleanupCore(TownRepository.CleanupJob job) {
        TownRepository.CleanupPart core = job.parts().get("CORE_DB");
        if (core != null && "DONE".equals(core.state())) return CompletableFuture.completedFuture(null);
        return api.db().asyncRun(() -> {
            try {
                repository.markCleanupState(job.town().id(), "CORE_PURGE_PENDING", null);
                repository.destroyTownCore(job.town());
                repository.markCleanupPart(job.town().id(), "CORE_DB", true, null);
                repository.markCleanupState(job.town().id(), "CORE_DELETED", null);
                plugin.getLogger().info("[TownCleanup] CORE_DB ok town=" + job.town().id());
            } catch (Throwable error) {
                repository.markCleanupPart(job.town().id(), "CORE_DB", false, rootMessage(error));
                repository.noteCleanupRetry(job.town().id(), rootMessage(error));
                throw error;
            }
        });
    }

    private CompletableFuture<Void> cleanupWorld(TownRepository.CleanupJob job) {
        TownRepository.CleanupPart world = job.parts().get("WORLD");
        if (world != null && "DONE".equals(world.state())) return CompletableFuture.completedFuture(null);
        return api.db().asyncRun(() -> repository.markCleanupState(job.town().id(), "WORLD_CLEANUP_PENDING", null))
                .thenCompose(ignored -> {
                    try {
                        CompletableFuture<Void> future = worldCleanupHandler.cleanup(job);
                        return future == null ? CompletableFuture.<Void>failedFuture(new IllegalStateException("TownWorldCleanupHandler returned null")) : future;
                    } catch (Throwable error) {
                        return CompletableFuture.<Void>failedFuture(error);
                    }
                }).handle((ignored, error) -> {
                    String message = error == null ? null : rootMessage(error);
                    if (error != null) plugin.getLogger().warning("[TownCleanup] WORLD failed town=" + job.town().id() + " error=" + message + "; claims remain locked for retry");
                    CompletableFuture<Void> persisted = api.db().asyncRun(() -> {
                        repository.markCleanupPart(job.town().id(), "WORLD", error == null, message);
                        if (error != null) repository.noteCleanupRetry(job.town().id(), message);
                    });
                    if (error == null) return persisted;
                    return persisted.thenCompose(v -> CompletableFuture.<Void>failedFuture(error));
                }).thenCompose(v -> v);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) current = current.getCause();
        if (current == null) return "unknown error";
        return current.getMessage() == null || current.getMessage().isBlank() ? current.getClass().getSimpleName() : current.getMessage();
    }

    @FunctionalInterface
    public interface TownWorldCleanupHandler {
        CompletableFuture<Void> cleanup(TownRepository.CleanupJob job);
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

    private record DestroyPreparation(TownRepository.CleanupJob job, List<UUID> members, List<ChunkPos> chunks, OperationResult failure) {
        static DestroyPreparation success(TownRepository.CleanupJob job, List<UUID> members, List<ChunkPos> chunks) {
            return new DestroyPreparation(job, members, chunks, null);
        }
        static DestroyPreparation failure(OperationResult failure) {
            return new DestroyPreparation(null, List.of(), List.of(), failure);
        }
    }

    private record Membership(long townId, TownRole role) {
    }

    public record MemberInfo(UUID playerId, String name, TownRole role, boolean online) {
    }

    public record CoopRequestInfo(UUID playerId, String name, long createdAt) {
    }

    public record CoopDebugRequest(long townId, String townName, long createdAt, long ageMillis) {
    }

    public record CoopDebugInfo(UUID playerId, String runtimeMembership, String dbMembership,
                                List<CoopDebugRequest> requests, int runtimeMemberCount,
                                TownMemberLimitService.DebugInfo memberLimit) {
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
