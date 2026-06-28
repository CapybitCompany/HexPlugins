package hex.minions.service;

import hex.core.api.HexApi;
import hex.core.api.messaging.HexMessage;
import hex.core.api.messaging.HexMessageBus;
import hex.core.api.messaging.HexMessageData;
import hex.core.api.trigger.GameTrigger;
import hex.core.api.trigger.TriggerService;
import hex.core.api.ui.UiTokens;
import hex.collections.api.HexCollectionsApi;
import hex.collections.api.CollectionProgress;
import hex.minions.api.MinionMenuData;
import hex.minions.api.MinionView;
import hex.minions.api.MinionsListener;
import hex.minions.api.TownMinionMenuData;
import hex.minions.config.Definitions;
import hex.minions.config.ItemRequirement;
import hex.minions.config.MinionTypeDefinition;
import hex.minions.config.MinionsConfig;
import hex.minions.config.ResourceDefinition;
import hex.minions.config.ResourceDrop;
import hex.minions.config.StorageChestDefinition;
import hex.minions.config.StorageChestRegistry;
import hex.minions.config.TierDefinition;
import hex.minions.crafting.BoosterDefinition;
import hex.minions.crafting.SpecialItemRegistry;
import hex.minions.crafting.SpecialRecipeDefinition;
import hex.minions.config.UpgradeRequirements;
import hex.minions.database.MinionRepository;
import hex.minions.menu.MinionMenu;
import hex.minions.machine.MachineRegistry;
import hex.minions.model.MinionInstance;
import hex.minions.model.MinionLocation;
import hex.minions.model.MinionState;
import hex.minions.render.MinionRenderer;
import hex.minions.util.LocationKeys;
import hex.towns.api.TownsApi;
import hex.towns.model.Town;
import hex.towns.model.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Entity;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
    private volatile StorageChestRegistry storageChests;
    private volatile SpecialItemRegistry specialItems;
    private volatile MachineRegistry machines;
    private final HexMessageBus messageBus;
    private final TriggerService triggerService;
    private final HexCollectionsApi collections;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final ConcurrentMap<UUID, MinionInstance> minionsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<UUID>> minionsByTownUuid = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> minionByBlock = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<UUID>> minionsByChunk = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, SelectedMinionContext> selectedContext = new ConcurrentHashMap<>();
    private final Set<MinionsListener> listeners = ConcurrentHashMap.newKeySet();
    private final PriorityBlockingQueue<ScheduledAction> actionQueue = new PriorityBlockingQueue<>();
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dirtyRuntimeMinions = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dirtyStorageMinions = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dirtyAddonMinions = ConcurrentHashMap.newKeySet();
    private final Object mutationLock = new Object();
    private BukkitTask engineTask;
    private BukkitTask labelTask;
    private BukkitTask boosterParticleTask;
    private BukkitTask persistenceFlushTask;

    public MinionService(Plugin plugin, HexApi hex, TownsApi towns, HexCollectionsApi collections, MinionRepository repository,
                         MinionRenderer renderer, MinionItemFactory itemFactory, MinionsConfig config, Definitions definitions, StorageChestRegistry storageChests, SpecialItemRegistry specialItems) {
        this.plugin = plugin;
        this.hex = hex;
        this.towns = towns;
        this.collections = collections;
        this.repository = repository;
        this.renderer = renderer;
        this.itemFactory = itemFactory;
        this.config = config;
        this.definitions = definitions;
        this.storageChests = storageChests;
        this.specialItems = specialItems;
        this.machines = MachineRegistry.load(plugin);
        this.messageBus = hex.service(HexMessageBus.class).orElse(null);
        this.triggerService = findTriggerService(hex);
    }


    public HexCollectionsApi collections() { return collections; }

    public TownsApi towns() { return towns; }

    public void reload(MinionsConfig config, Definitions definitions, StorageChestRegistry storageChests, SpecialItemRegistry specialItems) {
        this.config = config;
        this.definitions = definitions;
        this.storageChests = storageChests;
        this.specialItems = specialItems;
        this.machines = MachineRegistry.load(plugin);
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
            index(minion);
            if (type != null) renderer.spawn(minion, type);
            // Po starcie serwera renderer ładuje chunk miniona, więc catch-up może od razu uwzględnić
            // także fizyczną skrzynkę storage stojącą obok miniona.
            applyOfflineCatchup(minion, now, "server-load");
            if (type != null) renderer.updateLabel(minion, type);
            schedule(minion);
        }
    }

    public void startTasks() {
        stopTasks();
        engineTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickEngine, config.engineIntervalTicks(), config.engineIntervalTicks());
        labelTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshLabels, config.labelRefreshTicks(), config.labelRefreshTicks());
        boosterParticleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBoostersAndParticles, 20L, 20L);
        persistenceFlushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushDirtyMinionsAsync, config.dirtyFlushIntervalTicks(), config.dirtyFlushIntervalTicks());
    }

    public void stopTasks() {
        if (engineTask != null) engineTask.cancel();
        if (labelTask != null) labelTask.cancel();
        if (boosterParticleTask != null) boosterParticleTask.cancel();
        if (persistenceFlushTask != null) persistenceFlushTask.cancel();
        flushDirtyMinionsSync();
        engineTask = null;
        labelTask = null;
        boosterParticleTask = null;
        persistenceFlushTask = null;
    }

    public boolean canAccessMinion(Player player, UUID minionId) {
        if (player == null || minionId == null) return false;
        MinionInstance minion = minionsById.get(minionId);
        return minion != null && towns.isMember(player.getUniqueId(), minion.townUuid());
    }

    public Optional<UUID> townUuidOfMinion(UUID minionId) {
        MinionInstance minion = minionId == null ? null : minionsById.get(minionId);
        return minion == null ? Optional.empty() : Optional.of(minion.townUuid());
    }

    public boolean canAccessTownAt(Player player, Location location) {
        if (player == null || location == null) return false;
        return towns.townAt(location).filter(town -> towns.isMember(player.getUniqueId(), town.id())).isPresent();
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
        int bonus = towns.getMetaInt(townUuid, config.limitMetaKey(), 0);
        return Math.min(config.hardCap(), Math.max(0, config.defaultTownLimit()) + Math.max(0, bonus));
    }

    public Optional<Town> findTown(UUID townUuid) {
        return towns.findTown(townUuid);
    }

    public Optional<Town> findTownByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        final Town[] found = new Town[1];
        towns.forEachTown(town -> {
            if (found[0] == null && town.name().equalsIgnoreCase(name)) found[0] = town;
        }, 100);
        return Optional.ofNullable(found[0]);
    }

    public void addMinionLimitBonus(UUID townUuid, int delta, String source) {
        if (townUuid == null || delta == 0) return;
        int current = towns.getMetaInt(townUuid, config.limitMetaKey(), 0);
        int next = Math.max(0, current + delta);
        towns.setMeta(townUuid, config.limitMetaKey(), String.valueOf(next));
        if (source != null && !source.isBlank()) {
            towns.setMeta(townUuid, "minions.limit-bonus-source." + source, String.valueOf(delta));
        }
    }

    public void observeMinion(UUID minionId) {
        if (minionId == null) return;
        MinionInstance minion = minionsById.get(minionId);
        if (minion == null) return;
        MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
        if (type == null || minion.state() != MinionState.ACTIVE) return;
        long now = System.currentTimeMillis();
        applyOfflineCatchup(minion, now, "observe-minion");
        if (isChunkLoaded(minion.location())) {
            renderer.spawn(minion, type);
            renderer.updateLabel(minion, type);
        }
    }

    public void observeChunk(org.bukkit.Chunk chunk) {
        if (chunk == null) return;
        Set<UUID> ids = minionsByChunk.get(LocationKeys.chunkKey(chunk));
        if (ids == null || ids.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (UUID id : Set.copyOf(ids)) {
            MinionInstance minion = minionsById.get(id);
            if (minion == null || minion.state() != MinionState.ACTIVE) continue;
            MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
            if (type == null) continue;
            applyOfflineCatchup(minion, now, "chunk-load");
            renderer.spawn(minion, type);
            renderer.updateLabel(minion, type);
            schedule(minion);
        }
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
            repository.recordTownMinionTier(minion.townInternalId(), minion.typeId(), minion.tier());
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
        Map<String, ItemStack> droppedAddons = drainAddonItems(minion);
        Map<String, Long> droppedStorage = minion.drainStorage();
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
                World minionWorld = Bukkit.getWorld(minion.location().world());
                Location dropLocation = minionWorld == null ? player.getLocation() : minionWorld.getBlockAt(minion.location().x(), minion.location().y(), minion.location().z()).getLocation();
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(itemFactory.createPickupItem(type, minion.tier(), minion.id()));
                leftover.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
                dropResources(dropLocation, droppedStorage);
                dropAddonItems(dropLocation, droppedAddons);
                removeLinkedStorageChest(minion, dropLocation, true);
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
        UpgradeRequirements requirements = next.upgradeRequirements();
        if (!hasInventoryCollectionRequirements(player, requirements)) {
            return CompletableFuture.completedFuture(OperationResult.fail("minions.upgrade.missing-requirements"));
        }
        if (!hasInventoryItemRequirements(player, requirements)) {
            return CompletableFuture.completedFuture(OperationResult.fail("minions.upgrade.missing-requirements"));
        }
        consumeInventoryCollectionRequirements(player, requirements);
        // Upgrade miniona ma zużywać fizyczne itemy z EQ gracza, a nie odejmować postęp kolekcji miasta.
        // Kolekcja pozostaje historycznym/progresowym licznikiem dla unlocków, questów i wiki.
        consumeInventoryItemRequirements(player, requirements);
        minion.incrementTier();
        minion.setStorageLimit(next.storage());
        return hex.db().async(() -> {
            repository.updateRuntime(minion);
            repository.upsertStorage(minion.id(), minion.storage());
            repository.updateTownMinionMaxTier(minion.townInternalId(), minion.typeId(), minion.tier());
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
                publishCollectionTriggers(minion, player.getUniqueId(), drained, "minions.resource.claimed");
            });
            return result;
        });
    }

    public CompletableFuture<OperationResult> withdrawStorageSlot(Player player, UUID minionId, int visibleStorageSlot) {
        MinionInstance minion = minionsById.get(minionId);
        if (minion == null) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.not-found"));
        if (!towns.isMember(player.getUniqueId(), minion.townUuid())) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.not-member"));
        if (visibleStorageSlot < 0) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.storage-empty"));

        StorageWithdrawal withdrawal;
        synchronized (mutationLock) {
            withdrawal = takeVisibleStorageStack(minion, visibleStorageSlot);
        }
        if (withdrawal == null) return CompletableFuture.completedFuture(OperationResult.fail("minions.error.storage-empty"));

        giveResources(player, Map.of(withdrawal.resourceId(), withdrawal.amount()));
        return hex.db().async(() -> {
            repository.updateRuntime(minion);
            repository.upsertStorage(minion.id(), minion.storage());
            repository.audit(minion.id(), minion.townInternalId(), player.getUniqueId(), "WITHDRAW_SLOT", withdrawal.resourceId() + "=" + withdrawal.amount());
            return OperationResult.ok("minions.collect.success");
        }).thenApply(result -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
                if (type != null) renderer.updateLabel(minion, type);
                publish("minions.storage.claimed", minion, player.getUniqueId(), Map.of("resource", withdrawal.resourceId(), "amount", String.valueOf(withdrawal.amount())));
                publishCollectionTriggers(minion, player.getUniqueId(), Map.of(withdrawal.resourceId(), withdrawal.amount()), "minions.resource.claimed");
                notifyChanged(minion);
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


    public void cleanupTownWorld(Town town, List<ChunkPos> chunks) {
        if (town == null || chunks == null || chunks.isEmpty()) return;
        World world = Bukkit.getWorld(town.world());
        if (world == null) return;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        for (ChunkPos pos : chunks) {
            org.bukkit.Chunk chunk = world.getChunkAt(pos.x(), pos.z());
            // Przy usuwaniu miasta celowo czyścimy tylko teren miasta. Usuwamy wszystkie specjalne
            // bloki HexMinions, minionowe skrzynki oraz zawartość zwykłych kontenerów w tych chunkach.
            for (Entity entity : chunk.getEntities()) {
                if (entity.getType() == EntityType.BLOCK_DISPLAY || entity.getType() == EntityType.TEXT_DISPLAY) {
                    entity.remove();
                }
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = minY; y < maxY; y++) {
                        Block block = chunk.getBlock(x, y, z);
                        boolean specialBlock = specialItems.readSpecialBlockId(block).isPresent();
                        boolean storageBlock = itemFactory.readStorageChestBlockId(block).isPresent();
                        if (block.getState() instanceof Container container) {
                            container.getInventory().clear();
                        }
                        if (specialBlock || storageBlock) {
                            block.setType(Material.AIR, false);
                        }
                    }
                }
            }
        }
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
            minions.add(toMenuData(sorted.get(i), i + 1, viewer));
        }
        return new TownMinionMenuData(townId.get(), name, minions.size(), maxMinions(townId.get()), minions);
    }

    @Override
    public Optional<MinionMenuData> minionData(Player viewer, UUID minionId) {
        MinionInstance minion = minionsById.get(minionId);
        if (minion == null || !towns.isMember(viewer.getUniqueId(), minion.townUuid())) return Optional.empty();
        return Optional.of(toMenuData(minion, 0, viewer));
    }

    @Override
    public Optional<MinionMenuData> minionByIndex(Player viewer, int index) {
        if (index <= 0) return Optional.empty();
        Optional<UUID> townId = towns.townIdOf(viewer.getUniqueId());
        if (townId.isEmpty()) return Optional.empty();
        List<MinionInstance> list = sortedTownMinions(townId.get());
        if (index > list.size()) return Optional.empty();
        return Optional.of(toMenuData(list.get(index - 1), index, viewer));
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

    public SpecialItemRegistry specialItems() { return specialItems; }


    public ItemStack recipeOutput(SpecialRecipeDefinition recipe) {
        return specialItems == null ? new ItemStack(Material.AIR) : specialItems.output(recipe, itemFactory, definitions);
    }

    public MachineRegistry machines() { return machines; }

    public boolean hasRecipeUnlocks(UUID townUuid, SpecialRecipeDefinition recipe) {
        if (recipe == null || recipe.unlock().isEmpty()) return true;
        long townInternalId = towns.findTown(townUuid).map(Town::internalId).orElse(0L);
        if (townInternalId <= 0) townInternalId = repository.findInternalTownId(townUuid).orElse(0L);
        if (townInternalId <= 0) {
            for (MinionInstance minion : sortedTownMinions(townUuid)) { townInternalId = minion.townInternalId(); break; }
        }
        for (Map.Entry<String, Integer> entry : recipe.unlock().townMinionLevels().entrySet()) {
            int maxTier = repository.townMinionMaxTier(townInternalId, entry.getKey());
            if (maxTier < entry.getValue()) return false;
        }
        if (collections != null) {
            for (Map.Entry<String, Long> entry : recipe.unlock().collections().entrySet()) {
                if (collectionAmount(townUuid, entry.getKey()) < entry.getValue()) return false;
            }
            for (Map.Entry<String, Integer> entry : recipe.unlock().collectionLevels().entrySet()) {
                try { collections.loadTown(townUuid); } catch (Throwable ignored) {}
                if (collections.getLevel(townUuid, entry.getKey()) < entry.getValue()) return false;
            }
            if (!meetsCollectionTierCounts(townUuid, recipe.unlock().collectionTierCounts())) return false;
        } else if (!recipe.unlock().collections().isEmpty() || !recipe.unlock().collectionLevels().isEmpty() || !recipe.unlock().collectionTierCounts().isEmpty()) return false;
        return true;
    }


    public boolean townHasMinionLevel(UUID townUuid, String typeId, int requiredTier) {
        if (townUuid == null || typeId == null || typeId.isBlank()) return false;
        long townInternalId = towns.findTown(townUuid).map(Town::internalId).orElse(0L);
        if (townInternalId <= 0) townInternalId = repository.findInternalTownId(townUuid).orElse(0L);
        if (townInternalId <= 0) {
            for (MinionInstance minion : sortedTownMinions(townUuid)) {
                townInternalId = minion.townInternalId();
                break;
            }
        }
        return townInternalId > 0 && repository.townMinionMaxTier(townInternalId, typeId.toLowerCase(java.util.Locale.ROOT)) >= Math.max(1, requiredTier);
    }

    public String recipeUnlockText(SpecialRecipeDefinition recipe) {
        if (recipe == null || recipe.unlock().isEmpty()) return "Brak";
        List<String> parts = new ArrayList<>();
        recipe.unlock().townMinionLevels().forEach((k, v) -> parts.add("minion " + k + " tier " + v));
        recipe.unlock().collections().forEach((k, v) -> parts.add("kolekcja " + k + " x" + v));
        recipe.unlock().collectionLevels().forEach((k, v) -> parts.add("kolekcja " + k + " tier " + v));
        recipe.unlock().collectionTierCounts().forEach(req -> parts.add(req.count() + " kolekcje tier " + req.tier() + "+" + (req.distinct() ? " (różne)" : "")));
        return String.join(", ", parts);
    }

    private boolean meetsCollectionTierCounts(UUID townUuid, List<hex.minions.crafting.RecipeUnlockRequirement.CollectionTierCount> requirements) {
        if (requirements.isEmpty()) return true;
        if (collections == null || townUuid == null) return false;
        try { collections.loadTown(townUuid); } catch (Throwable ignored) {}
        try {
            Map<String, CollectionProgress> progressMap = collections.getAllCollections(townUuid);
            Set<String> usedDistinct = new java.util.HashSet<>();
            for (hex.minions.crafting.RecipeUnlockRequirement.CollectionTierCount req : requirements) {
                int count = 0;
                for (Map.Entry<String, CollectionProgress> entry : progressMap.entrySet()) {
                    if (req.distinct() && usedDistinct.contains(entry.getKey())) continue;
                    if (entry.getValue().level() >= req.tier()) {
                        count++;
                        if (req.distinct()) usedDistinct.add(entry.getKey());
                        if (count >= req.count()) break;
                    }
                }
                if (count < req.count()) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isSpecialStationBlock(Block block, String stationId) {
        return specialItems.readSpecialBlockId(block).map(stationId::equalsIgnoreCase).orElse(false);
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

    private void markMinionDirty(MinionInstance minion, boolean runtime, boolean storage, boolean addons) {
        if (minion == null) return;
        if (runtime) dirtyRuntimeMinions.add(minion.id());
        if (storage) dirtyStorageMinions.add(minion.id());
        if (addons) dirtyAddonMinions.add(minion.id());
    }

    private void flushDirtyMinionsAsync() {
        java.util.Set<UUID> runtimeIds = drainDirty(dirtyRuntimeMinions, config.dirtyFlushMaxRows());
        java.util.Set<UUID> storageIds = drainDirty(dirtyStorageMinions, config.dirtyFlushMaxRows());
        java.util.Set<UUID> addonIds = drainDirty(dirtyAddonMinions, config.dirtyFlushMaxRows());
        if (runtimeIds.isEmpty() && storageIds.isEmpty() && addonIds.isEmpty()) return;
        Map<UUID, MinionInstance> snapshot = new java.util.LinkedHashMap<>();
        for (UUID id : runtimeIds) { MinionInstance m = minionsById.get(id); if (m != null) snapshot.put(id, m); }
        for (UUID id : storageIds) { MinionInstance m = minionsById.get(id); if (m != null) snapshot.put(id, m); }
        for (UUID id : addonIds) { MinionInstance m = minionsById.get(id); if (m != null) snapshot.put(id, m); }
        hex.db().asyncRun(() -> {
            repository.updateRuntimeBatch(runtimeIds.stream().map(snapshot::get).filter(java.util.Objects::nonNull).toList());
            repository.upsertStorageBatch(storageIds.stream().map(snapshot::get).filter(java.util.Objects::nonNull).toList());
            repository.upsertAddonItemsBatch(addonIds.stream().map(snapshot::get).filter(java.util.Objects::nonNull).toList());
        }).exceptionally(ex -> {
            dirtyRuntimeMinions.addAll(runtimeIds);
            dirtyStorageMinions.addAll(storageIds);
            dirtyAddonMinions.addAll(addonIds);
            plugin.getLogger().warning("Nie udało się zapisać batcha minionów: " + rootMessage(ex));
            return null;
        });
    }

    private java.util.Set<UUID> drainDirty(java.util.Set<UUID> source, int maxRows) {
        java.util.Set<UUID> result = new java.util.LinkedHashSet<>();
        for (UUID id : java.util.Set.copyOf(source)) {
            if (result.size() >= Math.max(1, maxRows)) break;
            if (source.remove(id)) result.add(id);
        }
        return result;
    }

    private void flushDirtyMinionsSync() {
        java.util.Set<UUID> runtimeIds = drainDirty(dirtyRuntimeMinions, Integer.MAX_VALUE);
        java.util.Set<UUID> storageIds = drainDirty(dirtyStorageMinions, Integer.MAX_VALUE);
        java.util.Set<UUID> addonIds = drainDirty(dirtyAddonMinions, Integer.MAX_VALUE);
        try {
            repository.updateRuntimeBatch(runtimeIds.stream().map(minionsById::get).filter(java.util.Objects::nonNull).toList());
            repository.upsertStorageBatch(storageIds.stream().map(minionsById::get).filter(java.util.Objects::nonNull).toList());
            repository.upsertAddonItemsBatch(addonIds.stream().map(minionsById::get).filter(java.util.Objects::nonNull).toList());
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Nie udało się zapisać minionów przy wyłączaniu: " + rootMessage(throwable));
        }
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
            if (next.whenMillis() != minion.nextActionAt()) {
                schedule(minion);
                continue;
            }
            if (config.requireLoadedChunk() && !isChunkLoaded(minion.location())) {
                minion.setNextActionAt(now + 60_000L);
                schedule(minion);
                continue;
            }
            ensureActiveBooster(minion, type, now);
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
        boolean anyAccepted = false;
        for (GeneratedDrop generated : rollDrops(minion, type)) {
            String generatedResource = producedResourceId(minion, type, generated.resourceId());
            long accepted = storeGeneratedResource(minion, generatedResource, generated.amount());
            if (accepted > 0) {
                anyAccepted = true;
                publishCollectionTriggers(minion, null, Map.of(generatedResource, accepted), "minions.resource.generated");
            }
        }
        if (anyAccepted && isMobMinion(type)) {
            publishMinionMobKill(minion, null, type.id(), 1L);
        }
        TierDefinition tier = type.tier(minion.tier());
        minion.setLastActionAt(now);
        minion.setNextActionAt(now + boostedDelayMillis(minion, type, tier, now));
        markMinionDirty(minion, true, true, false);
    }

    private void applyOfflineCatchup(MinionInstance minion, long now, String reason) {
        if (!config.offlineEnabled() || minion.state() != MinionState.ACTIVE) return;
        MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
        if (type == null || type.resourceTable().isEmpty()) return;
        TierDefinition tier = type.tier(minion.tier());
        long maxElapsed = config.offlineMaxHours() * 3_600_000L;
        long elapsed = Math.min(Math.max(0L, now - minion.lastActionAt()), maxElapsed);
        if (elapsed <= 0) return;

        // Offline catch-up jest rozliczany z ostatniej aktywności miniona. Nie wymaga stale
        // załadowanego chunka; przy ponownym wejściu w chunk/menu przeliczamy ile akcji powinno
        // się wydarzyć i wypełniamy internal storage oraz, jeśli chunk jest załadowany, skrzynkę obok.
        long delay = Math.max(50L, boostedDelayMillis(minion, type, tier, minion.lastActionAt()));
        long possible = Math.min(config.offlineMaxActionsPerMinion(), elapsed / delay);
        if (possible <= 0) return;

        long actionsDone = 0L;
        long mobKillsGenerated = 0L;
        boolean changed = false;
        boolean storageCapped = false;
        for (long action = 0; action < possible; action++) {
            boolean anyAccepted = false;
            List<GeneratedDrop> rolledDrops = rollDrops(minion, type);
            if (rolledDrops.isEmpty()) {
                // Akcja produkcyjna mogła się odbyć, ale tabela dropów nie wylosowała żadnego itemu.
                // Nie traktujemy tego jako przepełnienia storage.
                actionsDone++;
                continue;
            }
            for (GeneratedDrop generated : rolledDrops) {
                String generatedResource = producedResourceId(minion, type, generated.resourceId());
                long accepted = storeGeneratedResource(minion, generatedResource, generated.amount());
                if (accepted > 0) {
                    anyAccepted = true;
                    changed = true;
                }
            }
            if (!anyAccepted) {
                // Jeżeli offline catch-up dochodzi do limitu storage miniona + skrzynki,
                // nie zostawiamy zaległego "długu produkcji". W przeciwnym razie po wyjęciu
                // jednego stacka menu natychmiast ponownie wypełniało slot kolejnymi akcjami
                // z tej samej godziny offline. Limit storage ma być realnym limitem produkcji.
                storageCapped = true;
                break;
            }
            if (anyAccepted && isMobMinion(type)) {
                mobKillsGenerated++;
            }
            actionsDone++;
        }

        if (storageCapped) {
            minion.setLastActionAt(now);
            minion.setNextActionAt(now + delay);
        } else {
            long accountedTime = Math.min(elapsed, actionsDone * delay);
            minion.setLastActionAt(Math.min(now, minion.lastActionAt() + accountedTime));
            minion.setNextActionAt(minion.lastActionAt() + delay);
        }
        if (changed || actionsDone > 0 || storageCapped) {
            MinionTypeDefinition finalType = type;
            long finalActionsDone = actionsDone;
            long finalMobKillsGenerated = mobKillsGenerated;
            String finalReason = reason;
            if (finalMobKillsGenerated > 0L && isMobMinion(type)) {
                publishMinionMobKill(minion, null, type.id(), finalMobKillsGenerated);
            }
            markMinionDirty(minion, true, true, false);
            hex.db().asyncRun(() -> repository.audit(minion.id(), minion.townInternalId(), null, "OFFLINE_CATCHUP", "reason=" + finalReason + ";actions=" + finalActionsDone));
            renderer.updateLabel(minion, finalType);
            reschedule(minion);
            notifyChanged(minion);
        }
    }

    private long storeGeneratedResource(MinionInstance minion, String resourceId, long amount) {
        if (amount <= 0) return 0L;
        long accepted = 0L;

        // Priorytet: najpierw fizyczna skrzynka rozszerzeń obok miniona, potem internal storage.
        // Dzięki temu skrzynka jest realnym rozszerzeniem magazynu, a nie tylko dekoracją.
        if (isChunkLoaded(minion.location())) {
            accepted += depositToAdjacentStorageChest(minion, resourceId, amount);
        }
        long remaining = amount - accepted;
        if (remaining > 0) {
            accepted += minion.addStorage(resourceId, remaining);
        }
        if (accepted > 0 && hasActiveCompressorUpdate(minion, definitions.minionTypes().get(minion.typeId()))) {
            compactStoredResource(minion, resourceId);
        }
        return accepted;
    }

    private void schedule(MinionInstance minion) {
        if (minion.state() == MinionState.ACTIVE && queued.add(minion.id())) {
            actionQueue.add(new ScheduledAction(minion.id(), minion.nextActionAt()));
        }
    }

    private void reschedule(MinionInstance minion) {
        queued.remove(minion.id());
        schedule(minion);
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

    private int effectiveStorageUsed(MinionInstance minion) {
        long used = minion.storageUsed();
        if (Bukkit.isPrimaryThread() && isChunkLoaded(minion.location())) {
            Chest chest = storageChest(minion).orElse(null);
            if (chest != null) used += countStorageChestItems(chest);
        }
        return (int) Math.min(Integer.MAX_VALUE, used);
    }

    private int effectiveStorageLimit(MinionInstance minion) {
        long limit = Math.max(0, minion.storageLimit());
        if (Bukkit.isPrimaryThread() && isChunkLoaded(minion.location())) {
            Chest chest = storageChest(minion).orElse(null);
            if (chest != null) limit += (long) storageChestSlots(chest) * 64L;
        }
        return (int) Math.min(Integer.MAX_VALUE, limit);
    }

    private int countStorageChestItems(Chest chest) {
        if (chest == null) return 0;
        Inventory inventory = chest.getBlockInventory();
        int limit = Math.min(storageChestSlots(chest), inventory.getSize());
        long amount = 0L;
        for (int i = 0; i < limit; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && !item.getType().isAir()) amount += item.getAmount();
        }
        return (int) Math.min(Integer.MAX_VALUE, amount);
    }

    private int storageChestSlots(Chest chest) {
        Optional<String> id = itemFactory.readStorageChestBlockId(chest.getBlock());
        if (id.isPresent()) {
            return storageChests.find(id.get()).map(StorageChestDefinition::slots).orElse(3);
        }
        String name = chest.getCustomName();
        if (name == null) return 3;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("x-large") || lower.contains("xlarge")) return 21;
        if (lower.contains("large")) return 15;
        if (lower.contains("medium")) return 5;
        return 3;
    }

    private Optional<Chest> storageChest(MinionInstance minion) {
        return storageChest(minion, null);
    }

    private Optional<Chest> storageChest(MinionInstance minion, Location ignoredLocation) {
        World world = Bukkit.getWorld(minion.location().world());
        if (world == null) return Optional.empty();
        Block base = world.getBlockAt(minion.location().x(), minion.location().y(), minion.location().z());
        List<Chest> matches = new ArrayList<>();
        for (Block block : adjacentBlocks(base)) {
            if (ignoredLocation != null && block.getLocation().equals(ignoredLocation)) continue;
            if (isStorageChestBlock(block) && block.getState() instanceof Chest chest) {
                matches.add(chest);
            }
        }
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private List<Block> adjacentBlocks(Block block) {
        return List.of(
                block.getRelative(1, 0, 0),
                block.getRelative(-1, 0, 0),
                block.getRelative(0, 0, 1),
                block.getRelative(0, 0, -1)
        );
    }

    public boolean isValidStorageChestItem(ItemStack item) {
        if (storageChestIdFromItem(item).isPresent()) return true;
        return !storageChests.requireSpecialItem() && item != null && isStorageChestMaterial(item.getType());
    }

    public void markPlacedStorageChest(Block block, ItemStack item) {
        storageChestIdFromItem(item).ifPresent(id -> itemFactory.markStorageChestBlock(block, id));
    }

    private Optional<String> storageChestIdFromItem(ItemStack item) {
        Optional<MinionItemFactory.StorageChestItemData> direct = itemFactory.readStorageChestItem(item);
        if (direct.isPresent()) return Optional.of(direct.get().id());
        return specialItems.readSpecialItemId(item)
                .map(String::toLowerCase)
                .flatMap(id -> switch (id) {
                    case "storage_expander" -> Optional.of("small");
                    case "medium_minion_storage" -> Optional.of("medium");
                    case "large_minion_storage" -> Optional.of("large");
                    case "iron_uranium_chest" -> Optional.of("iron_uranium");
                    default -> Optional.empty();
                });
    }

    private boolean isStorageChestBlock(Block block) {
        if (block == null || !isStorageChestMaterial(block.getType())) return false;
        if (itemFactory.readStorageChestBlockId(block).isPresent()) return true;
        return storageChests.allowPlainChestFallback();
    }

    private boolean isStorageChestMaterial(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST;
    }


    public ItemStack addonItem(UUID minionId, String slotId) {
        MinionInstance minion = minionsById.get(minionId);
        if (minion == null) return null;
        ItemStack item = minion.addonItems().get(slotId);
        return item == null ? null : item.clone();
    }

    public boolean wikiTestMode() {
        return config.wikiTestMode();
    }

    public ItemStack removeAddonItem(UUID minionId, String slotId) {
        MinionInstance minion = minionsById.get(minionId);
        if (minion == null || slotId == null || slotId.isBlank()) return null;
        ItemStack removed = minion.addonItems().remove(slotId);
        markMinionDirty(minion, false, false, true);
        notifyChanged(minion);
        return brokenIfCompressorUpdate(removed);
    }

    public OperationResult uninstallStorageChestFromMenu(Player player, UUID minionId) {
        MinionInstance minion = minionsById.get(minionId);
        if (minion == null) return OperationResult.fail("minions.error.not-found");
        if (!towns.isMember(player.getUniqueId(), minion.townUuid())) return OperationResult.fail("minions.error.not-member");
        Chest chest = storageChest(minion).orElse(null);
        if (chest == null) return OperationResult.fail("minions.storage-chest.error.not-found");
        Block block = chest.getBlock();
        Optional<String> id = itemFactory.readStorageChestBlockId(block);
        ItemStack storageItem = id.flatMap(storageChests::find)
                .map(def -> itemFactory.createStorageChestItem(def, 1))
                .orElseGet(() -> new ItemStack(block.getType() == Material.TRAPPED_CHEST ? Material.TRAPPED_CHEST : Material.CHEST, 1));
        Location dropLocation = block.getLocation();
        removeLinkedStorageChest(minion, dropLocation, false);
        player.getInventory().addItem(storageItem).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        return OperationResult.ok("minions.storage-chest.uninstall.success");
    }

    private Map<String, ItemStack> drainAddonItems(MinionInstance minion) {
        java.util.LinkedHashMap<String, ItemStack> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ItemStack> entry : minion.addonItems().entrySet()) {
            ItemStack item = entry.getValue();
            if (item == null || item.getType().isAir()) continue;
            ItemStack dropped = brokenIfCompressorUpdate(item);
            if (dropped != null && !dropped.getType().isAir()) copy.put(entry.getKey(), dropped);
        }
        minion.addonItems().clear();
        return copy;
    }

    private void dropAddonItems(Location location, Map<String, ItemStack> items) {
        if (location == null || location.getWorld() == null || items == null || items.isEmpty()) return;
        for (ItemStack item : items.values()) {
            if (item == null || item.getType().isAir()) continue;
            location.getWorld().dropItemNaturally(location, item.clone());
        }
    }

    public boolean hasStorageChest(UUID minionId) {
        MinionInstance minion = minionsById.get(minionId);
        return minion != null && storageChest(minion).isPresent();
    }

    public Optional<Chest> storageChestForMenu(UUID minionId) {
        MinionInstance minion = minionsById.get(minionId);
        return minion == null ? Optional.empty() : storageChest(minion);
    }

    public boolean isLinkedStorageChest(Block block) {
        return isStorageChestBlock(block) && adjacentMinion(block.getLocation()).isPresent();
    }

    public OperationResult installStorageChestFromMenu(Player player, UUID minionId, ItemStack item) {
        MinionInstance minion = minionsById.get(minionId);
        if (minion == null) return OperationResult.fail("minions.error.not-found");
        if (!towns.isMember(player.getUniqueId(), minion.townUuid())) return OperationResult.fail("minions.error.not-member");
        String storageChestId = storageChestIdFromItem(item).orElse("");
        if (storageChestId.isBlank()) return OperationResult.fail("minions.storage-chest.error.special-required");
        if (storageChest(minion).isPresent()) return OperationResult.fail("minions.storage-chest.error.already-has");
        World world = Bukkit.getWorld(minion.location().world());
        if (world == null) return OperationResult.fail("minions.move.error.location-invalid");
        Block base = world.getBlockAt(minion.location().x(), minion.location().y(), minion.location().z());
        Block target = base.getRelative(BlockFace.WEST);
        if (!target.getType().isAir()) return OperationResult.fail("minions.storage-chest.error.no-space-left");
        if (touchesOtherChest(target.getLocation())) return OperationResult.fail("minions.storage-chest.error.next-to-chest");
        StorageChestDefinition definition = storageChests.find(storageChestId).orElse(storageChests.first().orElse(null));
        target.setType(definition != null && definition.material() == Material.TRAPPED_CHEST ? Material.TRAPPED_CHEST : Material.CHEST);
        itemFactory.markStorageChestBlock(target, storageChestId);
        item.setAmount(item.getAmount() - 1);
        spawnIronStorageOverlay(target, storageChestId);
        return OperationResult.ok("minions.storage-chest.install.success");
    }

    private void spawnIronStorageOverlay(Block chest, String storageChestId) {
        if (chest == null || storageChestId == null || !storageChestId.equalsIgnoreCase("iron_uranium")) return;
        try {
            Location loc = chest.getLocation().add(0, 0, 0);
            BlockDisplay display = (BlockDisplay) chest.getWorld().spawnEntity(loc, EntityType.BLOCK_DISPLAY);
            display.setBlock(Material.IRON_BLOCK.createBlockData());
            // 10/16 wysokości: zostawia widoczną klapę skrzyni, ale przykrywa korpus żelazną teksturą.
            display.setTransformation(new Transformation(new Vector3f(-0.03f, 0.0f, -0.03f), new AxisAngle4f(), new Vector3f(1.06f, 0.625f, 1.06f), new AxisAngle4f()));
            display.setPersistent(true);
        } catch (Throwable ignored) {
            // Wizualna nakładka jest dodatkiem; sama skrzynka storage pozostaje działająca bez BlockDisplay.
        }
    }


    private void removeLinkedStorageChest(MinionInstance minion, Location dropLocation, boolean dropStorageItem) {
        if (minion == null || dropLocation == null || dropLocation.getWorld() == null) return;
        Chest chest = storageChest(minion).orElse(null);
        if (chest == null) return;
        Block block = chest.getBlock();
        Inventory inventory = chest.getBlockInventory();
        for (ItemStack item : inventory.getContents()) {
            if (item != null && !item.getType().isAir()) {
                dropLocation.getWorld().dropItemNaturally(dropLocation, item.clone());
            }
        }
        inventory.clear();
        if (dropStorageItem) {
            Optional<String> id = itemFactory.readStorageChestBlockId(block);
            ItemStack storageItem = id.flatMap(storageChests::find)
                    .map(def -> itemFactory.createStorageChestItem(def, 1))
                    .orElseGet(() -> new ItemStack(block.getType() == Material.TRAPPED_CHEST ? Material.TRAPPED_CHEST : Material.CHEST, 1));
            dropLocation.getWorld().dropItemNaturally(dropLocation, storageItem);
        }
        removeStorageChestOverlays(block);
        block.setType(Material.AIR, false);
    }

    private void removeStorageChestOverlays(Block chestBlock) {
        if (chestBlock == null || chestBlock.getWorld() == null) return;
        Location center = chestBlock.getLocation().add(0.5D, 0.5D, 0.5D);
        for (Entity entity : chestBlock.getWorld().getNearbyEntities(center, 1.25D, 1.25D, 1.25D)) {
            if (entity instanceof BlockDisplay) entity.remove();
        }
    }


    public boolean isResourceItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return true;
        for (ResourceDefinition def : definitions.resources().values()) {
            if (matchesResource(item, def)) return true;
        }
        return false;
    }

    public boolean isAllowedAddonItem(UUID minionId, ItemStack item) {
        if (item == null || item.getType().isAir()) return true;
        MinionInstance minion = minionsById.get(minionId);
        if (minion == null) return false;
        MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
        if (type == null) return false;
        if (storageChestIdFromItem(item).isPresent()) return false;
        if (isSupportedBoosterItem(type, item)) return true;
        if (isSupportedAutoSmelterItem(type, item)) return true;
        if (isSupportedCompressorUpdateItem(type, item)) return true;
        for (int tier = 1; tier <= type.maxTier(); tier++) {
            for (ItemRequirement requirement : type.tier(tier).upgradeRequirements().items()) {
                if (requirement.matches(item)) return true;
            }
        }
        return false;
    }

    public void saveMinionMenu(UUID minionId, Inventory inv) {
        MinionInstance minion = minionsById.get(minionId);
        if (minion == null) return;
        minion.setAddonItem("addon_1", cleanupAddonPlaceholder(minion.id(), inv.getItem(MinionMenu.ADDON_SLOT_1)));
        minion.setAddonItem("addon_2", cleanupAddonPlaceholder(minion.id(), inv.getItem(MinionMenu.ADDON_SLOT_2)));
        MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
        if (type != null) ensureActiveBooster(minion, type, System.currentTimeMillis());
        markMinionDirty(minion, false, false, true);
    }

    private ItemStack cleanupAddonPlaceholder(UUID minionId, ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        return isAllowedAddonItem(minionId, item) ? item.clone() : null;
    }

    public void saveStorageChestMenu(UUID minionId, Inventory inv) {
        Optional<Chest> chest = storageChestForMenu(minionId);
        if (chest.isEmpty()) return;
        Inventory chestInv = chest.get().getBlockInventory();
        int usable = storageChestSlots(chest.get());
        int limit = Math.min(Math.min(usable, inv.getSize()), chestInv.getSize());
        for (int i = 0; i < limit; i++) chestInv.setItem(i, inv.getItem(i));
        for (int i = limit; i < chestInv.getSize(); i++) chestInv.setItem(i, null);
    }

    public int storageChestSlotCapacity(UUID minionId) {
        Optional<Chest> chest = storageChestForMenu(minionId);
        return chest.map(this::storageChestSlots).orElse(0);
    }

    private StorageWithdrawal takeVisibleStorageStack(MinionInstance minion, int visibleStorageSlot) {
        MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
        if (type == null) return null;
        int unlockedSlots = unlockedStorageSlots(minion, type);
        if (visibleStorageSlot >= unlockedSlots) return null;

        int index = 0;
        List<String> resourceIds = minion.storage().keySet().stream().sorted().toList();
        for (String resourceId : resourceIds) {
            long remaining = minion.storage().getOrDefault(resourceId, 0L);
            ResourceDefinition def = definitions.resources().get(resourceId);
            int stackSize = def == null ? 64 : Math.max(1, Math.min(64, def.stackSize()));
            while (remaining > 0 && index < unlockedSlots) {
                long amount = Math.min(stackSize, remaining);
                if (index == visibleStorageSlot) {
                    java.util.LinkedHashMap<String, Long> newStorage = new java.util.LinkedHashMap<>(minion.storage());
                    long newAmount = Math.max(0L, newStorage.getOrDefault(resourceId, 0L) - amount);
                    if (newAmount <= 0L) newStorage.remove(resourceId); else newStorage.put(resourceId, newAmount);
                    minion.replaceStorage(newStorage);
                    return new StorageWithdrawal(resourceId, amount);
                }
                remaining -= amount;
                index++;
            }
        }
        return null;
    }

    private record StorageWithdrawal(String resourceId, long amount) {}

    private void dropResources(Location location, Map<String, Long> resources) {
        if (location.getWorld() == null) return;
        for (Map.Entry<String, Long> entry : resources.entrySet()) {
            ResourceDefinition def = definitions.resources().get(entry.getKey());
            if (def == null) continue;
            long remaining = entry.getValue();
            while (remaining > 0) {
                int amount = (int) Math.min(def.stackSize(), remaining);
                ItemStack stack = resourceStack(def, amount);
                location.getWorld().dropItemNaturally(location, stack);
                remaining -= amount;
            }
        }
    }

    private void giveResources(Player player, Map<String, Long> resources) {
        for (Map.Entry<String, Long> entry : resources.entrySet()) {
            ResourceDefinition def = definitions.resources().get(entry.getKey());
            if (def == null) continue;
            long remaining = entry.getValue();
            while (remaining > 0) {
                int amount = (int) Math.min(def.stackSize(), remaining);
                ItemStack stack = resourceStack(def, amount);
                player.getInventory().addItem(stack).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                remaining -= amount;
            }
        }
    }

    private ItemStack resourceStack(ResourceDefinition def, int amount) {
        ItemStack stack = new ItemStack(def.material(), Math.max(1, Math.min(def.stackSize(), amount)));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (def.customModelData() > 0) meta.setCustomModelData(def.customModelData());
            if (def.displayName() != null && !def.displayName().isBlank()) {
                meta.displayName(miniMessage.deserialize(def.displayName()));
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }


    private boolean hasTownCollectionRequirements(UUID townId, UpgradeRequirements requirements) {
        if (requirements.collectionAmounts().isEmpty()) return true;
        if (collections == null || townId == null) return false;
        try {
            collections.loadTown(townId);
        } catch (Throwable ignored) {
            // If the cache is already loaded or the implementation refuses a sync load, getAmount below remains the source of truth.
        }
        for (Map.Entry<String, Long> req : requirements.collectionAmounts().entrySet()) {
            if (collectionAmount(townId, req.getKey()) < req.getValue()) return false;
        }
        return true;
    }

    private long collectionAmount(UUID townId, String collectionId) {
        if (collections == null || townId == null || collectionId == null) return 0L;
        return collections.getAmount(townId, collectionId);
    }

    private boolean hasInventoryCollectionRequirements(Player player, UpgradeRequirements requirements) {
        for (Map.Entry<String, Long> req : requirements.collectionAmounts().entrySet()) {
            ResourceDefinition resource = resourceByCollectionId(req.getKey());
            if (resource == null) return false;
            if (countInventoryResourceWeighted(player, resource) < req.getValue()) return false;
        }
        return true;
    }

    private void consumeInventoryCollectionRequirements(Player player, UpgradeRequirements requirements) {
        for (Map.Entry<String, Long> req : requirements.collectionAmounts().entrySet()) {
            ResourceDefinition resource = resourceByCollectionId(req.getKey());
            if (resource != null) removeInventoryResourceWeighted(player, resource, req.getValue());
        }
    }


    private ResourceDefinition resourceByCollectionId(String collectionId) {
        if (collectionId == null) return null;
        for (ResourceDefinition resource : definitions.resources().values()) {
            if (collectionId.equalsIgnoreCase(resource.collectionId())) return resource;
        }
        return definitions.resources().get(collectionId);
    }

    private long countInventoryResourceWeighted(Player player, ResourceDefinition resource) {
        long amount = 0L;
        for (ItemStack item : player.getInventory().getContents()) {
            amount += resourceUnitValue(item, resource) * (long) (item == null ? 0 : item.getAmount());
        }
        return amount;
    }

    private void removeInventoryResourceWeighted(Player player, ResourceDefinition resource, long amount) {
        if (amount <= 0) return;
        long remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        int[] values = {1, compressedValue(resource), superCompressedValue(resource)};
        for (int value : values) {
            for (int i = 0; i < contents.length && remaining > 0; i++) {
                ItemStack item = contents[i];
                if (resourceUnitValue(item, resource) != value) continue;
                int remove = (int) Math.min(item.getAmount(), (remaining + value - 1) / value);
                item.setAmount(item.getAmount() - remove);
                remaining -= (long) remove * value;
                if (item.getAmount() <= 0) contents[i] = null;
            }
        }
        player.getInventory().setContents(contents);
    }

    private int resourceUnitValue(ItemStack item, ResourceDefinition resource) {
        if (item == null || item.getType().isAir() || resource == null) return 0;
        if (resource.compressionEnabled() && resource.blockConvertible()) {
            String id = specialItems.readSpecialItemId(item).orElse("");
            if (id.equalsIgnoreCase("compressed_" + resource.id())) return compressedValue(resource);
            if (id.equalsIgnoreCase("super_compressed_" + resource.id())) return superCompressedValue(resource);
            if (!id.isBlank()) return 0;
        }
        return matchesResource(item, resource) ? 1 : 0;
    }

    private int compressedValue(ResourceDefinition resource) {
        return specialItems == null ? 128 : specialItems.compressedUnitValue();
    }

    private int superCompressedValue(ResourceDefinition resource) {
        return specialItems == null ? compressedValue(resource) * 32 * 5 : specialItems.superCompressedUnitValue();
    }

    private boolean hasInventoryItemRequirements(Player player, UpgradeRequirements requirements) {
        for (ItemRequirement requirement : requirements.items()) {
            if (countInventoryItems(player, requirement) < requirement.amount()) return false;
        }
        return true;
    }

    private long countInventoryItems(Player player, ItemRequirement requirement) {
        long amount = 0L;
        for (ItemStack item : player.getInventory().getContents()) {
            if (requirement.matches(item)) amount += item.getAmount();
        }
        return amount;
    }

    private void consumeInventoryItemRequirements(Player player, UpgradeRequirements requirements) {
        for (ItemRequirement requirement : requirements.items()) {
            if (requirement.consume()) removeInventoryItems(player, requirement, requirement.amount());
        }
    }

    private void removeInventoryItems(Player player, ItemRequirement requirement, long amount) {
        if (amount <= 0) return;
        long remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (!requirement.matches(item)) continue;
            int remove = (int) Math.min(item.getAmount(), remaining);
            item.setAmount(item.getAmount() - remove);
            remaining -= remove;
            if (item.getAmount() <= 0) contents[i] = null;
        }
        player.getInventory().setContents(contents);
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
        return toMenuData(minion, slotHint, null);
    }

    private MinionMenuData toMenuData(MinionInstance minion, int slotHint, Player viewer) {
        // DeluxeMenus/PlaceholderAPI potrafi liczyc placeholdery poza glownym watkiem.
        // Offline catch-up dotyka swiata (chunk, skrzynia, encje labeli), wiec nie wolno go
        // uruchamiac podczas asynchronicznego renderowania menu.
        if (Bukkit.isPrimaryThread()) {
            applyOfflineCatchup(minion, System.currentTimeMillis(), "menu-open");
        }
        MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
        TierDefinition tier = type == null ? new TierDefinition(minion.tier(), 0, minion.storageLimit(), 1, UpgradeRequirements.empty()) : type.tier(minion.tier());
        int effectiveStorageUsed = effectiveStorageUsed(minion);
        int effectiveStorageLimit = effectiveStorageLimit(minion);
        int percent = effectiveStorageLimit <= 0 ? 0 : (int) Math.min(100, Math.round(effectiveStorageUsed * 100.0 / effectiveStorageLimit));
        boolean canUpgrade = type != null && minion.tier() < type.maxTier();
        long now = System.currentTimeMillis();
        if (type != null) ensureActiveBooster(minion, type, now);
        int activeTier = minion.hasActiveBooster(now) ? minion.activeBoosterTier() : 0;
        long boosterSeconds = minion.hasActiveBooster(now) ? Math.max(0L, (minion.boosterExpiresAt() - now + 999L) / 1000L) : 0L;
        Optional<BoosterDefinition> activeBooster = activeBooster(minion, now);
        double boosterPercent = activeBooster.map(BoosterDefinition::speedBoostPercent).orElse(0.0D);
        int boosterDuration = activeBooster.map(BoosterDefinition::durationSeconds).orElse(0);
        return new MinionMenuData(minion.id(), shortId(minion.id()), minion.typeId(), type == null ? minion.typeId() : type.displayName(), minion.tier(), type == null ? minion.tier() : type.maxTier(),
                minion.location().world(), minion.location().x(), minion.location().y(), minion.location().z(), effectiveStorageUsed, effectiveStorageLimit, percent,
                tier.actionTimeSeconds(), minion.state().name(), canUpgrade, requirementsText(type, minion.tier() + 1, minion.townUuid(), viewer), slotHint, unlockedStorageSlots(minion, type),
                activeTier, boosterSeconds, boosterDuration, type == null ? 0 : queuedBoosterItems(minion, type), boosterPercent,
                type == null ? "PLAYER_HEAD" : type.itemHeadMaterial(), Map.copyOf(minion.storage()));
    }


    private int unlockedStorageSlots(MinionInstance minion, MinionTypeDefinition type) {
        if (type == null) return 1;
        int baseSlots = type.tier(minion.tier()).storageSlots();
        // Storage jest teraz rozszerzane przez fizyczną skrzynkę obok miniona.
        // Item storage_expander nie dodaje już wirtualnych slotów w menu miniona.
        return Math.max(1, Math.min(9, baseSlots));
    }

    private boolean isStorageExpanderItem(ItemStack item) {
        return specialItems != null
                && specialItems.readSpecialItemId(item)
                .map(id -> id.equalsIgnoreCase("storage_expander"))
                .orElse(false);
    }

    private boolean isCompressorUpdateItem(ItemStack item) {
        return specialItems != null
                && specialItems.readSpecialItemId(item)
                .map(id -> id.equalsIgnoreCase("compressor_update"))
                .orElse(false);
    }

    private boolean isSupportedCompressorUpdateItem(MinionTypeDefinition type, ItemStack item) {
        return isCompressorUpdateItem(item) && typeSupportsCompression(type);
    }

    private boolean typeSupportsCompression(MinionTypeDefinition type) {
        if (type == null) return false;
        for (ResourceDrop drop : type.resourceTable()) {
            ResourceDefinition resource = definitions.resources().get(drop.resourceId());
            if (resource == null || !resource.compressionEnabled() || !resource.blockConvertible()) continue;
            if (definitions.resources().containsKey("compressed_" + resource.id().toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    private boolean hasActiveCompressorUpdate(MinionInstance minion, MinionTypeDefinition type) {
        if (minion == null || type == null || !typeSupportsCompression(type)) return false;
        return minion.addonItems().values().stream().anyMatch(this::isCompressorUpdateItem);
    }

    private void compactStoredResource(MinionInstance minion, String resourceId) {
        if (minion == null || resourceId == null || resourceId.isBlank()) return;
        ResourceDefinition resource = definitions.resources().get(resourceId);
        if (resource == null || !resource.compressionEnabled() || !resource.blockConvertible()) return;
        String compressedId = "compressed_" + resource.id().toLowerCase(java.util.Locale.ROOT);
        if (!definitions.resources().containsKey(compressedId)) return;
        long unit = specialItems == null ? 128L : Math.max(1, specialItems.compressedUnitValue());
        long current = minion.storage().getOrDefault(resource.id(), 0L);
        if (current < unit) return;
        long compressed = current / unit;
        long remainder = current % unit;
        java.util.LinkedHashMap<String, Long> newStorage = new java.util.LinkedHashMap<>(minion.storage());
        if (remainder <= 0L) newStorage.remove(resource.id()); else newStorage.put(resource.id(), remainder);
        newStorage.put(compressedId, newStorage.getOrDefault(compressedId, 0L) + compressed);
        minion.replaceStorage(newStorage);
    }

    private ItemStack brokenIfCompressorUpdate(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        if (!isCompressorUpdateItem(item)) return item.clone();
        ItemStack broken = specialItems == null ? null : specialItems.createItem("damaged_compressor_update", Math.max(1, item.getAmount()));
        if (broken == null || broken.getType().isAir()) return null;
        return broken;
    }

    private boolean isSupportedAutoSmelterItem(MinionTypeDefinition type, ItemStack item) {
        if (specialItems == null || type == null || item == null || item.getType().isAir()) return false;
        if (type.autoSmelter() == null || !type.autoSmelter().enabled()) return false;
        String expected = type.autoSmelter().requiredSpecialItem();
        return specialItems.readSpecialItemId(item).map(id -> id.equalsIgnoreCase(expected)).orElse(false);
    }

    private boolean hasActiveAutoSmelter(MinionInstance minion, MinionTypeDefinition type) {
        if (minion == null || type == null || type.autoSmelter() == null || !type.autoSmelter().enabled()) return false;
        return minion.addonItems().values().stream().anyMatch(item -> isSupportedAutoSmelterItem(type, item));
    }

    private String producedResourceId(MinionInstance minion, MinionTypeDefinition type, String baseResourceId) {
        if (!hasActiveAutoSmelter(minion, type)) return baseResourceId;
        return type.autoSmelter().outputFor(baseResourceId);
    }

    private boolean isSupportedBoosterItem(MinionTypeDefinition type, ItemStack item) {
        if (specialItems == null || type == null || item == null || item.getType().isAir()) return false;
        return specialItems.boosterByItem(item)
                .map(booster -> type.supportedBoosterTiers().contains(booster.tier()))
                .orElse(false);
    }

    private Optional<BoosterDefinition> queuedBooster(MinionInstance minion, MinionTypeDefinition type) {
        ItemStack item = minion.addonItems().get("addon_1");
        if (!isSupportedBoosterItem(type, item)) return Optional.empty();
        return specialItems.boosterByItem(item);
    }

    private int queuedBoosterItems(MinionInstance minion, MinionTypeDefinition type) {
        ItemStack item = minion.addonItems().get("addon_1");
        return isSupportedBoosterItem(type, item) ? Math.max(0, item.getAmount()) : 0;
    }

    private Optional<BoosterDefinition> activeBooster(MinionInstance minion, long nowMillis) {
        if (specialItems == null || !minion.hasActiveBooster(nowMillis)) return Optional.empty();
        return specialItems.booster(minion.activeBoosterTier());
    }

    private void ensureActiveBooster(MinionInstance minion, MinionTypeDefinition type, long nowMillis) {
        if (minion == null || type == null || specialItems == null) return;
        if (minion.hasActiveBooster(nowMillis)) return;
        if (minion.activeBoosterTier() > 0 && minion.boosterExpiresAt() <= nowMillis) minion.clearActiveBooster();
        Optional<BoosterDefinition> queued = queuedBooster(minion, type);
        if (queued.isEmpty()) return;
        ItemStack item = minion.addonItems().get("addon_1");
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return;
        item.setAmount(item.getAmount() - 1);
        if (item.getAmount() <= 0) minion.addonItems().remove("addon_1"); else minion.addonItems().put("addon_1", item);
        BoosterDefinition booster = queued.get();
        minion.setActiveBooster(booster.tier(), nowMillis + booster.durationSeconds() * 1000L);
        markMinionDirty(minion, false, false, true);
        notifyChanged(minion);
    }

    private long boostedDelayMillis(MinionInstance minion, MinionTypeDefinition type, TierDefinition tier, long nowMillis) {
        ensureActiveBooster(minion, type, nowMillis);
        double multiplier = activeBooster(minion, nowMillis).map(BoosterDefinition::actionTimeMultiplier).orElse(1.0D);
        return Math.max(50L, Math.round(tier.actionTimeSeconds() * 1000.0D * multiplier));
    }

    private void tickBoostersAndParticles() {
        long now = System.currentTimeMillis();
        for (MinionInstance minion : minionsById.values()) {
            if (minion.state() != MinionState.ACTIVE) continue;
            MinionTypeDefinition type = definitions.minionTypes().get(minion.typeId());
            if (type == null) continue;
            ensureActiveBooster(minion, type, now);
            activeBooster(minion, now).ifPresent(booster -> spawnBoosterParticles(minion, booster));
        }
    }

    private void spawnBoosterParticles(MinionInstance minion, BoosterDefinition booster) {
        World world = Bukkit.getWorld(minion.location().world());
        if (world == null) return;
        Location center = new Location(world, minion.location().x() + 0.5D, minion.location().y() + booster.particleYOffset(), minion.location().z() + 0.5D);
        try {
            world.spawnParticle(booster.particle(), center, booster.particleCount(), booster.particleRadius(), 0.35D, booster.particleRadius(), 0.01D);
        } catch (Throwable ignored) {
            world.spawnParticle(Particle.FLAME, center, Math.max(1, booster.particleCount()), booster.particleRadius(), 0.35D, booster.particleRadius(), 0.01D);
        }
    }

    private String requirementsText(MinionTypeDefinition type, int tier, UUID townId, Player viewer) {
        if (type == null || tier > type.maxTier()) return "-";
        UpgradeRequirements req = type.tier(tier).upgradeRequirements();
        if (req.emptyRequirements()) return "-";
        List<String> parts = new ArrayList<>();
        req.collectionAmounts().forEach((collectionId, required) -> {
            ResourceDefinition resource = resourceByCollectionId(collectionId);
            long current = viewer != null && resource != null
                    ? countInventoryResourceWeighted(viewer, resource)
                    : collectionAmount(townId, collectionId);
            long missing = Math.max(0L, required - current);
            String label = resource == null ? collectionId : stripMini(resource.displayName());
            String source = viewer != null && resource != null ? "EQ" : "kolekcja";
            parts.add(source + ":" + label + " " + current + "/" + required + " (brakuje " + missing + ")");
        });
        for (ItemRequirement item : req.items()) {
            long current = viewer == null ? 0L : countInventoryItems(viewer, item);
            long missing = Math.max(0L, item.amount() - current);
            parts.add("item:" + item.displayName() + " " + current + "/" + item.amount() + " (brakuje " + missing + ")" + (item.consume() ? "" : " (tylko posiadanie)"));
        }
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

    private List<GeneratedDrop> rollDrops(MinionInstance minion, MinionTypeDefinition type) {
        if (type.resourceTable().isEmpty()) return List.of();
        if ("WEIGHTED_ONE".equalsIgnoreCase(type.dropSelectionMode())) {
            double roll = ThreadLocalRandom.current().nextDouble();
            double cursor = 0.0D;
            for (ResourceDrop drop : type.resourceTable()) {
                cursor += Math.max(0.0D, adjustedDropChance(minion, drop));
                if (roll <= cursor) {
                    return List.of(new GeneratedDrop(drop.resourceId(), randomAmount(drop)));
                }
            }
            return List.of();
        }
        List<GeneratedDrop> generated = new ArrayList<>();
        for (ResourceDrop drop : type.resourceTable()) {
            if (ThreadLocalRandom.current().nextDouble() <= adjustedDropChance(minion, drop)) {
                generated.add(new GeneratedDrop(drop.resourceId(), randomAmount(drop)));
            }
        }
        return generated;
    }

    private double adjustedDropChance(MinionInstance minion, ResourceDrop drop) {
        double chance = drop.chance();
        if (drop.specialDrop()) {
            chance += Math.max(0, minion.tier() - drop.specialDropScalingFromTier() + 1) * Math.max(0.0D, drop.specialDropPerTierBonus());
            if (drop.specialDropUpgradeItem() != null && !drop.specialDropUpgradeItem().isBlank() && minion.addonItems().values().stream().anyMatch(item -> specialItems.readSpecialItemId(item).map(id -> id.equalsIgnoreCase(drop.specialDropUpgradeItem())).orElse(false))) {
                chance += Math.max(0.0D, drop.specialDropUpgradeBonus());
            }
        }
        return Math.max(0.0D, Math.min(1.0D, chance));
    }

    private int randomAmount(ResourceDrop drop) {
        return drop.amountMin() == drop.amountMax()
                ? drop.amountMin()
                : ThreadLocalRandom.current().nextInt(drop.amountMin(), drop.amountMax() + 1);
    }

    private boolean isMobMinion(MinionTypeDefinition type) {
        return type != null && ("combat".equalsIgnoreCase(type.category()) || "mob".equalsIgnoreCase(type.category()));
    }

    private void publishMinionMobKill(MinionInstance minion, UUID actor, String mobType, long amount) {
        if (triggerService == null || amount <= 0L) return;
        HexMessageData data = HexMessageData.builder()
                .put("schemaVersion", 1)
                .put("townId", minion.townUuid().toString())
                .put("playerUuid", actor == null ? "" : actor.toString())
                .put("minionId", minion.id().toString())
                .put("minionType", minion.typeId())
                .put("mobType", mobType)
                .put("mob-type", mobType)
                .put("amount", amount)
                .put("source", "MINION_MOB_KILL")
                .build();
        publishGameTrigger("minions.mob.killed", data);
    }

    private void publishCollectionTriggers(MinionInstance minion, UUID actor, Map<String, Long> resources, String triggerId) {
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
            publishGameTrigger(triggerId, data);
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

    private String stripMini(String input) {
        if (input == null) return "";
        return input.replaceAll("<[^>]+>", "").trim();
    }

    private String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private String rootMessage(Throwable throwable) {
        Throwable t = throwable;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private record GeneratedDrop(String resourceId, int amount) {
    }

    private record ScheduledAction(UUID minionId, long whenMillis) implements Comparable<ScheduledAction> {
        @Override public int compareTo(ScheduledAction other) { return Long.compare(whenMillis, other.whenMillis); }
    }

    private record SelectedMinionContext(UUID minionId, long expiresAtMillis) {
    }
}




