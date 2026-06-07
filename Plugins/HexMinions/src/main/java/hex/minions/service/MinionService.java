package hex.minions.service;

import hex.core.api.HexApi;
import hex.core.api.messaging.HexMessage;
import hex.core.api.messaging.HexMessageBus;
import hex.core.api.messaging.HexMessageData;
import hex.core.api.trigger.GameTrigger;
import hex.core.api.trigger.TriggerService;
import hex.core.api.ui.UiTokens;
import hex.minions.api.MinionMenuData;
import hex.minions.api.MinionView;
import hex.minions.api.MinionsListener;
import hex.minions.api.TownMinionMenuData;
import hex.minions.config.Definitions;
import hex.minions.config.MinionTypeDefinition;
import hex.minions.config.MinionsConfig;
import hex.minions.config.ResourceDefinition;
import hex.minions.config.ResourceDrop;
import hex.minions.config.TierDefinition;
import hex.minions.database.MinionRepository;
import hex.minions.model.MinionInstance;
import hex.minions.model.MinionLocation;
import hex.minions.model.MinionState;
import hex.minions.render.MinionRenderer;
import hex.minions.util.LocationKeys;
import hex.towns.api.TownsApi;
import hex.towns.model.Town;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

public final class MinionService implements MinionMenuDataService {
    private final Plugin plugin;
    private final HexApi hex;
    private final TownsApi towns;
    private final MinionRepository repository;
    private final MinionRenderer renderer;
    private final MinionItemFactory itemFactory;
    private volatile MinionsConfig config;
    private volatile Definitions definitions;
    private final HexMessageBus messageBus;
    private final TriggerService triggerService;

    private final ConcurrentMap<UUID, MinionInstance> minionsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<UUID>> minionsByTownUuid = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> minionByBlock = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<UUID>> minionsByChunk = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, SelectedMinionContext> selectedContext = new ConcurrentHashMap<>();
    private final Set<MinionsListener> listeners = ConcurrentHashMap.newKeySet();
    private final PriorityBlockingQueue<ScheduledAction> actionQueue = new PriorityBlockingQueue<>();
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private final Object mutationLock = new Object();
    private BukkitTask engineTask;
    private BukkitTask labelTask;

    public MinionService(Plugin plugin, HexApi hex, TownsApi towns, MinionRepository repository,
                         MinionRenderer renderer, MinionItemFactory itemFactory, MinionsConfig config, Definitions definitions) {
        this.plugin = plugin;
        this.hex = hex;
        this.towns = towns;
        this.repository = repository;
        this.renderer = renderer;
        this.itemFactory = itemFactory;
        this.config = config;
        this.definitions = definitions;
        this.messageBus = hex.service(HexMessageBus.class).orElse(null);
        this.triggerService = findTriggerService(hex);
    }

    public void reload(MinionsConfig config, Definitions definitions) {
        this.config = config;
        this.definitions = definitions;
        for (MinionInstance minion : minionsById.values()) {
            MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
            if (type == null) {
                minion.setState(MinionState.DISABLED_MISSING_TYPE);
            } else {
                minion.setStorageLimit(type.tier(minion.tier()).storage());
                renderer.updateLabel(minion, type);
            }
        }
    }

    public void load(List<MinionInstance> minions) {
        minionsById.clear();
        minionsByTownUuid.clear();
        minionByBlock.clear();
        minionsByChunk.clear();
        actionQueue.clear();
        queued.clear();
        long now = System.currentTimeMillis();
        for (MinionInstance minion : minions) {
            MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
            if (type == null) minion.setState(MinionState.DISABLED_MISSING_TYPE);
            applyOfflineCatchup(minion, now);
            index(minion);
            if (type != null) renderer.spawn(minion, type);
            schedule(minion);
        }
    }

    public void startTasks() {
        stopTasks();
        engineTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickEngine, config.engineIntervalTicks(), config.engineIntervalTicks());
        labelTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshLabels, config.labelRefreshTicks(), config.labelRefreshTicks());
    }

    public void stopTasks() {
        if (engineTask != null) engineTask.cancel();
        if (labelTask != null) labelTask.cancel();
        engineTask = null;
        labelTask = null;
    }

    public Optional<MinionView> findView(UUID id) {
        return Optional.ofNullable(minionsById.get(id)).map(this::toView);
    }

    public List<MinionView> viewsOfTown(UUID townUuid) {
        return sortedTownMinions(townUuid).stream().map(this::toView).toList();
    }

    public int countMinions(UUID townUuid) {
        return minionsByTownUuid.getOrDefault(townUuid, Set.of()).size();
    }

    public int maxMinions(UUID townUuid) {
        int meta = towns.getMetaInt(townUuid, config.limitMetaKey(), config.defaultTownLimit());
        return Math.min(config.hardCap(), Math.max(config.defaultTownLimit(), meta));
    }

    public boolean canPlace(Player player, Location location, String typeId) {
        return validatePlacement(player, location, typeId, null).success();
    }

    public CompletableFuture<OperationResult> place(Player player, Location location, ItemStack item) {
        Optional<MinionItemFactory.MinionItemData> data = itemFactory.read(item);
        if (data.isEmpty()) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.unknown-type"));
        return place(player, location, data.get().typeId(), data.get().tier());
    }

    public CompletableFuture<OperationResult> place(Player player, Location location, String typeId, int tier) {
        OperationResult validation = validatePlacement(player, location, typeId, null);
        if (!validation.success()) return CompletableFuture.completedFuture(validation);
        Town town = towns.townAt(location).orElseThrow();
        MinionTypeDefinition type = definitions.requireType(typeId);
        TierDefinition tierDef = type.tier(Math.max(1, Math.min(tier, type.maxTier())));
        long now = System.currentTimeMillis();
        UUID id = UUID.randomUUID();
        MinionLocation minionLocation = MinionLocation.from(location);
        MinionInstance minion = new MinionInstance(id, town.internalId(), town.id(), player.getUniqueId(), type.id(), tierDef.tier(),
                minionLocation, MinionState.ACTIVE, now, now, now + tierDef.actionTimeSeconds() * 1000L,
                0, tierDef.storage(), type.appearanceId());
        return hex.db().async(() -> {
            repository.insertMinion(minion);
            repository.audit(minion.id(), minion.townInternalId(), player.getUniqueId(), "PLACE", minion.location().compact());
            return OperationResult.ok("minions.place.success", UiTokens.of("id", shortId(id)));
        }).thenApply(result -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                index(minion);
                renderer.spawn(minion, type);
                schedule(minion);
                publish("minions.placed", minion, player.getUniqueId(), Map.of());
                notifyChanged(minion);
            });
            return result;
        }).exceptionally(ex -> OperationResult.fail("minions.error.db", UiTokens.of("error", rootMessage(ex))));
    }

    public CompletableFuture<OperationResult> pickup(Player player, UUID minionId) {
        MinionInstance minion = minionsById.get(minionId);
        if (minion == null) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.not-found"));
        if (!towns.isMember(player.getUniqueId(), minion.townUuid())) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.not-member"));
        MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
        if (type == null) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.unknown-type"));
        synchronized (mutationLock) {
            minion.setState(MinionState.DELETING);
        }
        return hex.db().async(() -> {
            repository.deleteMinion(minion.id());
            repository.audit(minion.id(), minion.townInternalId(), player.getUniqueId(), "PICKUP", minion.location().compact());
            return OperationResult.ok("minions.pickup.success", UiTokens.of("id", shortId(minion.id())));
        }).thenApply(result -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                unindex(minion);
                renderer.despawn(minion.id());
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(itemFactory.createPickupItem(type, minion.tier(), minion.id()));
                leftover.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
                publish("minions.picked_up", minion, player.getUniqueId(), Map.of());
            });
            return result;
        }).exceptionally(ex -> OperationResult.fail("minions.error.db", UiTokens.of("error", rootMessage(ex))));
    }

    public CompletableFuture<OperationResult> move(Player player, UUID minionId, Location targetLocation) {
        MinionInstance minion = minionsById.get(minionId);
        if (minion == null) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.not-found"));
        if (!config.relocationEnabled()) return CompletableFuture.completedFuture(OperationResult.fail("minions.move.error.disabled"));
        if (!towns.isMember(player.getUniqueId(), minion.townUuid())) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.not-member"));
        OperationResult validation = validatePlacement(player, targetLocation, minion.typeId(), minion.id());
        if (!validation.success()) return CompletableFuture.completedFuture(validation);
        Optional<Town> targetTown = towns.townAt(targetLocation);
        if (targetTown.isEmpty() || !targetTown.get().id().equals(minion.townUuid())) {
            return CompletableFuture.completedFuture(OperationResult.fail("minions.move.error.not-same-town"));
        }
        MinionLocation oldLocation = minion.location();
        MinionLocation newLocation = MinionLocation.from(targetLocation);
        if (!config.relocationUsePlayerYaw()) newLocation = new MinionLocation(newLocation.world(), newLocation.x(), newLocation.y(), newLocation.z(), oldLocation.yaw());
        MinionLocation finalNewLocation = newLocation;
        return hex.db().async(() -> {
            boolean changed = repository.moveMinion(minion.id(), finalNewLocation);
            if (!changed) return OperationResult.fail("minions.move.error.location-occupied");
            repository.audit(minion.id(), minion.townInternalId(), player.getUniqueId(), "MOVE", oldLocation.compact() + " -> " + finalNewLocation.compact());
            return OperationResult.ok("minions.move.success", UiTokens.of("id", shortId(minion.id())));
        }).thenApply(result -> {
            if (result.success()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    unindex(minion);
                    minion.setLocation(finalNewLocation);
                    index(minion);
                    MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
                    if (type != null) renderer.spawn(minion, type);
                    publish("minions.moved", minion, player.getUniqueId(), Map.of("from", oldLocation.compact(), "to", finalNewLocation.compact()));
                    notifyChanged(minion);
                });
            }
            return result;
        }).exceptionally(ex -> OperationResult.fail("minions.error.db", UiTokens.of("error", rootMessage(ex))));
    }

    public CompletableFuture<OperationResult> upgrade(Player player, UUID minionId) {
        MinionInstance minion = minionsById.get(minionId);
        if (minion == null) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.not-found"));
        if (!towns.isMember(player.getUniqueId(), minion.townUuid())) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.not-member"));
        MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
        if (type == null || minion.tier() >= type.maxTier()) return CompletableFuture.completedFuture(OperationResult.fail("minions.upgrade.missing-requirements"));
        TierDefinition next = type.tier(minion.tier() + 1);
        for (Map.Entry<String, Long> req : next.upgradeResources().entrySet()) {
            if (countInventoryResource(player, req.getKey()) < req.getValue()) {
                return CompletableFuture.completedFuture(OperationResult.fail("minions.upgrade.missing-requirements"));
            }
        }
        for (Map.Entry<String, Long> req : next.upgradeResources().entrySet()) {
            removeInventoryResource(player, req.getKey(), req.getValue());
        }
        minion.incrementTier();
        minion.setStorageLimit(next.storage());
        return hex.db().async(() -> {
            repository.updateRuntime(minion);
            repository.upsertStorage(minion.id(), minion.storage());
            repository.audit(minion.id(), minion.townInternalId(), player.getUniqueId(), "UPGRADE", "tier=" + minion.tier());
            return OperationResult.ok("minions.upgrade.success", UiTokens.of("tier", String.valueOf(minion.tier())));
        }).thenApply(result -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                renderer.updateLabel(minion, type);
                publish("minions.upgraded", minion, player.getUniqueId(), Map.of("tier", String.valueOf(minion.tier())));
                notifyChanged(minion);
            });
            return result;
        }).exceptionally(ex -> OperationResult.fail("minions.error.db", UiTokens.of("error", rootMessage(ex))));
    }

    public CompletableFuture<OperationResult> collect(Player player, UUID minionId) {
        MinionInstance minion = minionsById.get(minionId);
        if (minion == null) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.not-found"));
        if (!towns.isMember(player.getUniqueId(), minion.townUuid())) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.not-member"));
        Map<String, Long> drained = minion.drainStorage();
        if (drained.isEmpty()) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.storage-empty"));
        giveResources(player, drained);
        return hex.db().async(() -> {
            repository.updateRuntime(minion);
            repository.upsertStorage(minion.id(), minion.storage());
            repository.audit(minion.id(), minion.townInternalId(), player.getUniqueId(), "COLLECT", drained.toString());
            return OperationResult.ok("minions.collect.success");
        }).thenApply(result -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
                if (type != null) renderer.updateLabel(minion, type);
                publish("minions.storage.claimed", minion, player.getUniqueId(), Map.of());
                publishCollectionTriggers(minion, player.getUniqueId(), drained);
            });
            return result;
        });
    }

    public CompletableFuture<Void> purgeTown(UUID townUuid) {
        return hex.db().asyncRun(() -> repository.findInternalTownId(townUuid).ifPresent(repository::deleteByTownId)).thenRun(() ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (MinionInstance minion : sortedTownMinions(townUuid)) {
                        unindex(minion);
                        renderer.despawn(minion.id());
                    }
                })
        );
    }

    public void select(Player player, UUID minionId) {
        selectedContext.put(player.getUniqueId(), new SelectedMinionContext(minionId, System.currentTimeMillis() + config.selectedContextTtlSeconds() * 1000L));
    }

    @Override
    public TownMinionMenuData townData(Player viewer) {
        Optional<UUID> townId = towns.townIdOf(viewer.getUniqueId());
        if (townId.isEmpty()) return new TownMinionMenuData(null, "-", 0, 0, List.of());
        Optional<Town> town = towns.findTown(townId.get());
        String name = town.map(Town::name).orElse("-");
        List<MinionInstance> sorted = sortedTownMinions(townId.get());
        List<MinionMenuData> minions = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            minions.add(toMenuData(sorted.get(i), i + 1));
        }
        return new TownMinionMenuData(townId.get(), name, minions.size(), maxMinions(townId.get()), minions);
    }

    @Override
    public Optional<MinionMenuData> minionData(Player viewer, UUID minionId) {
        MinionInstance minion = minionsById.get(minionId);
        if (minion == null || !towns.isMember(viewer.getUniqueId(), minion.townUuid())) return Optional.empty();
        return Optional.of(toMenuData(minion, 0));
    }

    @Override
    public Optional<MinionMenuData> minionByIndex(Player viewer, int index) {
        if (index <= 0) return Optional.empty();
        Optional<UUID> townId = towns.townIdOf(viewer.getUniqueId());
        if (townId.isEmpty()) return Optional.empty();
        List<MinionInstance> list = sortedTownMinions(townId.get());
        if (index > list.size()) return Optional.empty();
        return Optional.of(toMenuData(list.get(index - 1), index));
    }

    public Optional<MinionMenuData> selectedMinion(Player viewer) {
        SelectedMinionContext context = selectedContext.get(viewer.getUniqueId());
        if (context == null) return Optional.empty();
        if (context.expiresAtMillis() < System.currentTimeMillis()) {
            selectedContext.remove(viewer.getUniqueId(), context);
            return Optional.empty();
        }
        return minionData(viewer, context.minionId());
    }

    public void registerListener(MinionsListener listener) {
        listeners.add(listener);
    }

    public Definitions definitions() {
        return definitions;
    }

    private OperationResult validatePlacement(Player player, Location location, String typeId, UUID movingMinionId) {
        if (location.getWorld() == null) return OperationResult.fail("minions.move.error.location-invalid");
        MinionTypeDefinition type = definitions.minionTypes().get(typeId);
        if (type == null || !type.enabled()) return OperationResult.fail("minions.error.unknown-type");
        Optional<Town> locTown = towns.townAt(location);
        if (locTown.isEmpty()) return OperationResult.fail("minions.error.not-in-own-town");
        if (!towns.isMember(player.getUniqueId(), locTown.get().id())) return OperationResult.fail("minions.error.not-member");
        if (movingMinionId == null && countMinions(locTown.get().id()) >= maxMinions(locTown.get().id())) {
            return OperationResult.fail("minions.error.limit-reached", UiTokens.of("limit", String.valueOf(maxMinions(locTown.get().id()))));
        }
        if (minionByBlock.containsKey(LocationKeys.blockKey(location)) && !minionByBlock.get(LocationKeys.blockKey(location)).equals(movingMinionId)) {
            return OperationResult.fail("minions.move.error.location-occupied");
        }
        if (type.requireSolidGround() && !location.clone().subtract(0, 1, 0).getBlock().getType().isSolid()) {
            return OperationResult.fail("minions.move.error.location-invalid");
        }
        if (type.blockedMaterials().contains(location.getBlock().getType())) {
            return OperationResult.fail("minions.move.error.location-invalid");
        }
        int minDistance = config.minDistanceBetweenMinions();
        for (MinionInstance other : minionsById.values()) {
            if (other.id().equals(movingMinionId) || !other.location().world().equals(location.getWorld().getName())) continue;
            double dx = other.location().x() - location.getBlockX();
            double dy = other.location().y() - location.getBlockY();
            double dz = other.location().z() - location.getBlockZ();
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < minDistance) return OperationResult.fail("minions.move.error.location-occupied");
        }
        return OperationResult.ok("minions.ok");
    }

    private void tickEngine() {
        long now = System.currentTimeMillis();
        int processed = 0;
        while (processed < config.maxActionsPerCycle()) {
            ScheduledAction next = actionQueue.peek();
            if (next == null || next.whenMillis() > now) break;
            actionQueue.poll();
            queued.remove(next.minionId());
            MinionInstance minion = minionsById.get(next.minionId());
            if (minion == null || minion.state() != MinionState.ACTIVE) continue;
            MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
            if (type == null) continue;
            if (config.requireLoadedChunk() && !isChunkLoaded(minion.location())) {
                minion.setNextActionAt(now + 60_000L);
                schedule(minion);
                continue;
            }
            generateOnce(minion, type, now);
            schedule(minion);
            processed++;
        }
    }

    private void generateOnce(MinionInstance minion, MinionTypeDefinition type, long now) {
        if (!minion.hasStorageSpace() && !hasAdjacentStorageChest(minion)) {
            minion.setNextActionAt(now + 60_000L);
            return;
        }
        for (ResourceDrop drop : type.resourceTable()) {
            if (ThreadLocalRandom.current().nextDouble() <= drop.chance()) {
                int amount = drop.amountMin() == drop.amountMax() ? drop.amountMin() : ThreadLocalRandom.current().nextInt(drop.amountMin(), drop.amountMax() + 1);
                long accepted = minion.addStorage(drop.resourceId(), amount);
                if (accepted < amount) {
                    depositToAdjacentStorageChest(minion, drop.resourceId(), amount - accepted);
                }
            }
        }
        TierDefinition tier = type.tier(minion.tier());
        minion.setLastActionAt(now);
        minion.setNextActionAt(now + tier.actionTimeSeconds() * 1000L);
        hex.db().asyncRun(() -> {
            repository.updateRuntime(minion);
            repository.upsertStorage(minion.id(), minion.storage());
        });
    }

    private void applyOfflineCatchup(MinionInstance minion, long now) {
        if (!config.offlineEnabled() || minion.state() != MinionState.ACTIVE) return;
        MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
        if (type == null) return;
        TierDefinition tier = type.tier(minion.tier());
        long elapsed = Math.min(now - minion.lastActionAt(), config.offlineMaxHours() * 3_600_000L);
        if (elapsed <= 0) return;
        long possible = Math.min(config.offlineMaxActionsPerMinion(), elapsed / Math.max(1, tier.actionTimeSeconds() * 1000L));
        for (long i = 0; i < possible && minion.hasStorageSpace(); i++) {
            for (ResourceDrop drop : type.resourceTable()) {
                if (drop.chance() >= 1.0) minion.addStorage(drop.resourceId(), drop.amountMin());
            }
        }
        minion.setLastActionAt(now);
        minion.setNextActionAt(now + tier.actionTimeSeconds() * 1000L);
        if (possible > 0) hex.db().asyncRun(() -> { repository.updateRuntime(minion); repository.upsertStorage(minion.id(), minion.storage()); });
    }

    private void schedule(MinionInstance minion) {
        if (minion.state() == MinionState.ACTIVE && queued.add(minion.id())) {
            actionQueue.add(new ScheduledAction(minion.id(), minion.nextActionAt()));
        }
    }

    private void refreshLabels() {
        for (MinionInstance minion : minionsById.values()) {
            MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
            if (type != null) renderer.updateLabel(minion, type);
        }
    }

    private boolean isChunkLoaded(MinionLocation location) {
        World world = Bukkit.getWorld(location.world());
        return world != null && world.isChunkLoaded(Math.floorDiv(location.x(), 16), Math.floorDiv(location.z(), 16));
    }

    public Optional<MinionInstance> adjacentMinion(Location location) {
        if (location == null || location.getWorld() == null) return Optional.empty();
        for (Block block : adjacentBlocks(location.getBlock())) {
            UUID minionId = minionByBlock.get(LocationKeys.blockKey(block.getLocation()));
            if (minionId != null) {
                MinionInstance minion = minionsById.get(minionId);
                if (minion != null) return Optional.of(minion);
            }
        }
        return Optional.empty();
    }

    public boolean hasAdjacentStorageChest(MinionInstance minion) {
        return storageChest(minion).isPresent();
    }

    public boolean hasAdjacentStorageChest(MinionInstance minion, Location ignoredLocation) {
        return storageChest(minion, ignoredLocation).isPresent();
    }

    public boolean touchesOtherChest(Location location) {
        if (location == null || location.getWorld() == null) return false;
        for (Block block : adjacentBlocks(location.getBlock())) {
            if (isStorageChestMaterial(block.getType())) return true;
        }
        return false;
    }

    private long depositToAdjacentStorageChest(MinionInstance minion, String resourceId, long amount) {
        if (amount <= 0) return 0L;
        Chest chest = storageChest(minion).orElse(null);
        ResourceDefinition def = definitions.resources().get(resourceId);
        if (chest == null || def == null) return 0L;
        long remaining = amount;
        while (remaining > 0) {
            int stackAmount = (int) Math.min(Math.min(def.stackSize(), def.material().getMaxStackSize()), remaining);
            ItemStack stack = new ItemStack(def.material(), stackAmount);
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && def.customModelData() > 0) {
                meta.setCustomModelData(def.customModelData());
                stack.setItemMeta(meta);
            }
            int accepted = addToLimitedChest(chest, stack, storageChestSlots(chest));
            remaining -= accepted;
            if (accepted <= 0) break;
        }
        return amount - remaining;
    }

    private int addToLimitedChest(Chest chest, ItemStack stack, int slots) {
        int remaining = stack.getAmount();
        var inventory = chest.getBlockInventory();
        int limit = Math.min(slots, inventory.getSize());
        for (int i = 0; i < limit && remaining > 0; i++) {
            ItemStack current = inventory.getItem(i);
            if (current == null || current.getType().isAir()) {
                ItemStack copy = stack.clone();
                copy.setAmount(Math.min(copy.getMaxStackSize(), remaining));
                inventory.setItem(i, copy);
                remaining -= copy.getAmount();
            } else if (current.isSimilar(stack) && current.getAmount() < current.getMaxStackSize()) {
                int add = Math.min(current.getMaxStackSize() - current.getAmount(), remaining);
                current.setAmount(current.getAmount() + add);
                remaining -= add;
            }
        }
        return stack.getAmount() - remaining;
    }

    private int storageChestSlots(Chest chest) {
        String name = chest.getCustomName();
        if (name == null) return 3;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("x-large") || lower.contains("xlarge")) return 21;
        if (lower.contains("large")) return 15;
        if (lower.contains("medium")) return 9;
        return 3;
    }

    private Optional<Chest> storageChest(MinionInstance minion) {
        return storageChest(minion, null);
    }

    private Optional<Chest> storageChest(MinionInstance minion, Location ignoredLocation) {
        World world = Bukkit.getWorld(minion.location().world());
        if (world == null) return Optional.empty();
        Block base = world.getBlockAt(minion.location().x(), minion.location().y(), minion.location().z());
        for (Block block : adjacentBlocks(base)) {
            if (ignoredLocation != null && block.getLocation().equals(ignoredLocation)) continue;
            if (isStorageChestMaterial(block.getType()) && block.getState() instanceof Chest chest) {
                return Optional.of(chest);
            }
        }
        return Optional.empty();
    }

    private List<Block> adjacentBlocks(Block block) {
        return List.of(
                block.getRelative(1, 0, 0),
                block.getRelative(-1, 0, 0),
                block.getRelative(0, 0, 1),
                block.getRelative(0, 0, -1)
        );
    }

    private boolean isStorageChestMaterial(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST;
    }

    private void giveResources(Player player, Map<String, Long> resources) {
        for (Map.Entry<String, Long> entry : resources.entrySet()) {
            ResourceDefinition def = definitions.resources().get(entry.getKey());
            if (def == null) continue;
            long remaining = entry.getValue();
            while (remaining > 0) {
                int amount = (int) Math.min(def.stackSize(), remaining);
                ItemStack stack = new ItemStack(def.material(), amount);
                player.getInventory().addItem(stack).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                remaining -= amount;
            }
        }
    }

    private long countInventoryResource(Player player, String resourceId) {
        ResourceDefinition def = definitions.resources().get(resourceId);
        if (def == null) return 0L;
        long amount = 0L;
        for (ItemStack item : player.getInventory().getContents()) {
            if (matchesResource(item, def)) amount += item.getAmount();
        }
        return amount;
    }

    private void removeInventoryResource(Player player, String resourceId, long amount) {
        ResourceDefinition def = definitions.resources().get(resourceId);
        if (def == null || amount <= 0) return;
        long remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (!matchesResource(item, def)) continue;
            int remove = (int) Math.min(item.getAmount(), remaining);
            item.setAmount(item.getAmount() - remove);
            remaining -= remove;
            if (item.getAmount() <= 0) contents[i] = null;
        }
        player.getInventory().setContents(contents);
    }

    private boolean matchesResource(ItemStack item, ResourceDefinition def) {
        if (item == null || item.getType().isAir() || item.getType() != def.material()) return false;
        if (def.customModelData() <= 0) return true;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasCustomModelData() && meta.getCustomModelData() == def.customModelData();
    }

    private void index(MinionInstance minion) {
        minionsById.put(minion.id(), minion);
        minionsByTownUuid.computeIfAbsent(minion.townUuid(), ignored -> ConcurrentHashMap.newKeySet()).add(minion.id());
        minionByBlock.put(LocationKeys.blockKey(minion.location()), minion.id());
        minionsByChunk.computeIfAbsent(LocationKeys.chunkKey(minion.location()), ignored -> ConcurrentHashMap.newKeySet()).add(minion.id());
    }

    private void unindex(MinionInstance minion) {
        minionsById.remove(minion.id());
        Set<UUID> townSet = minionsByTownUuid.get(minion.townUuid());
        if (townSet != null) townSet.remove(minion.id());
        minionByBlock.remove(LocationKeys.blockKey(minion.location()));
        Set<UUID> chunkSet = minionsByChunk.get(LocationKeys.chunkKey(minion.location()));
        if (chunkSet != null) chunkSet.remove(minion.id());
    }

    private List<MinionInstance> sortedTownMinions(UUID townUuid) {
        List<MinionInstance> list = new ArrayList<>();
        for (UUID id : minionsByTownUuid.getOrDefault(townUuid, Set.of())) {
            MinionInstance minion = minionsById.get(id);
            if (minion != null) list.add(minion);
        }
        list.sort(Comparator.comparingLong(MinionInstance::placedAt).thenComparing(MinionInstance::id));
        return list;
    }

    private MinionView toView(MinionInstance minion) {
        MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
        String name = type == null ? minion.typeId() : type.displayName();
        return new MinionView(minion.id(), minion.townUuid(), minion.typeId(), name, minion.tier(), minion.location().world(),
                minion.location().x(), minion.location().y(), minion.location().z(), minion.state(), minion.storageUsed(), minion.storageLimit(), Map.copyOf(minion.storage()));
    }

    private MinionMenuData toMenuData(MinionInstance minion, int slotHint) {
        MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
        TierDefinition tier = type == null ? new TierDefinition(minion.tier(), 0, minion.storageLimit(), 1, Map.of()) : type.tier(minion.tier());
        int percent = minion.storageLimit() <= 0 ? 0 : (int) Math.min(100, Math.round(minion.storageUsed() * 100.0 / minion.storageLimit()));
        boolean canUpgrade = type != null && minion.tier() < type.maxTier();
        return new MinionMenuData(minion.id(), shortId(minion.id()), minion.typeId(), type == null ? minion.typeId() : type.displayName(), minion.tier(), type == null ? minion.tier() : type.maxTier(),
                minion.location().world(), minion.location().x(), minion.location().y(), minion.location().z(), minion.storageUsed(), minion.storageLimit(), percent,
                tier.actionTimeSeconds(), minion.state().name(), canUpgrade, requirementsText(type, minion.tier() + 1), slotHint, Math.max(1, Math.min(9, tier.storageSlots())), Map.copyOf(minion.storage()));
    }

    private String requirementsText(MinionTypeDefinition type, int tier) {
        if (type == null || tier > type.maxTier()) return "-";
        Map<String, Long> req = type.tier(tier).upgradeResources();
        if (req.isEmpty()) return "-";
        List<String> parts = new ArrayList<>();
        req.forEach((k, v) -> parts.add(k + " x" + v));
        return String.join(", ", parts);
    }

    private void notifyChanged(MinionInstance minion) {
        MinionView view = toView(minion);
        listeners.forEach(listener -> listener.onMinionChanged(view));
    }

    private void publish(String channel, MinionInstance minion, UUID actor, Map<String, String> extra) {
        if (messageBus == null) return;
        HexMessageData.Builder builder = HexMessageData.builder()
                .put("townId", String.valueOf(minion.townInternalId()))
                .put("townUuid", minion.townUuid().toString())
                .put("minionId", minion.id().toString())
                .put("type", minion.typeId())
                .put("tier", minion.tier())
                .put("byUuid", actor == null ? "" : actor.toString());
        extra.forEach(builder::put);
        messageBus.publish(HexMessage.of(channel, plugin.getName(), builder.build()));
    }

    private void publishCollectionTriggers(MinionInstance minion, UUID actor, Map<String, Long> resources) {
        if (triggerService == null || resources.isEmpty()) return;
        for (Map.Entry<String, Long> entry : resources.entrySet()) {
            ResourceDefinition resource = definitions.resources().get(entry.getKey());
            if (resource == null || resource.collectionId() == null || resource.collectionId().isBlank()) continue;
            HexMessageData data = HexMessageData.builder()
                    .put("schemaVersion", 1)
                    .put("townId", minion.townUuid().toString())
                    .put("playerUuid", actor == null ? "" : actor.toString())
                    .put("minionId", minion.id().toString())
                    .put("minionType", minion.typeId())
                    .put("resourceId", entry.getKey())
                    .put("resource-id", entry.getKey())
                    .put("collectionId", resource.collectionId())
                    .put("amount", entry.getValue())
                    .put("source", "MINION_COLLECT")
                    .build();
            publishGameTrigger("minions.resource.claimed", data);
        }
    }

    private TriggerService findTriggerService(HexApi hex) {
        try {
            return hex.service(TriggerService.class).orElse(null);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not access HexCore trigger API: " + rootMessage(throwable));
        }
        return null;
    }

    private void publishGameTrigger(String triggerId, HexMessageData data) {
        try {
            triggerService.publish(GameTrigger.of(triggerId, plugin.getName(), data));
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not publish minion collection trigger '" + triggerId + "': " + rootMessage(throwable));
        }
    }

    private String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private String rootMessage(Throwable throwable) {
        Throwable t = throwable;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private record ScheduledAction(UUID minionId, long whenMillis) implements Comparable<ScheduledAction> {
        @Override public int compareTo(ScheduledAction other) { return Long.compare(whenMillis, other.whenMillis); }
    }

    private record SelectedMinionContext(UUID minionId, long expiresAtMillis) {
    }
}




