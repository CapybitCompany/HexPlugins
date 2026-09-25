package hexposterunki.listener;

import hex.core.api.ui.UiTokens;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.engine.LootService;
import hexposterunki.engine.OutpostEngine;
import hexposterunki.region.InventoryLocations;
import hexposterunki.region.RegionIndex;
import hexposterunki.ui.PosterunkiUi;
import hexposterunki.ui.UiKeys;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Permanent protection of every configured outpost region - also while no encounter is running.
 *
 * <p>Deliberately scoped to world integrity: PvP and PvE damage are handled in
 * {@link EncounterListener}, so combat mechanics are never blocked wholesale here. The damage
 * handlers in this class only cover decorative entities (item frames, armor stands), which no
 * legitimate fight needs to hit.
 *
 * <p>Administrators with {@code hexposterunki.admin.bypass} pass every check.
 */
public final class RegionProtectionListener implements Listener {

    public static final String BYPASS = "hexposterunki.admin.bypass";

    private final RegionIndex regions;
    private final OutpostEngine engine;
    private final PosterunkiUi ui;

    public RegionProtectionListener(RegionIndex regions, OutpostEngine engine, PosterunkiUi ui) {
        this.regions = Objects.requireNonNull(regions, "regions");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    private boolean bypass(Player player) {
        return player != null && player.hasPermission(BYPASS);
    }

    private void deny(Player player) {
        if (player != null) {
            ui.send(player, UiKeys.REGION_PROTECTED, new UiTokens());
        }
    }

    // ---------------------------------------------------------------- blocks

    /**
     * Also receives {@link BlockMultiPlaceEvent}: a bed or a door placed next to the border must not put
     * its other half inside, so every placed position is checked.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (bypass(event.getPlayer())) {
            return;
        }
        boolean touchesProtected = regions.isProtected(event.getBlock())
                || event instanceof BlockMultiPlaceEvent multi && touchesProtected(multi.getReplacedBlockStates());
        if (!touchesProtected) {
            return;
        }
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    /** Trees and other grown structures may not reach into a protected region, wherever they grow from. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (bypass(event.getPlayer()) || !touchesProtected(event.getBlocks())) {
            return;
        }
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    /** Bone meal may not change blocks inside a protected region, also when applied from outside. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (bypass(event.getPlayer())
                || !regions.isProtected(event.getBlock()) && !touchesProtected(event.getBlocks())) {
            return;
        }
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    /** True when any of the changed positions lies in a configured region or the pinned active geometry. */
    private boolean touchesProtected(List<BlockState> states) {
        for (BlockState state : states) {
            if (regions.isProtected(state.getLocation())) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (bypass(event.getPlayer()) || !regions.isProtected(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (bypass(event.getPlayer()) || !regions.isProtected(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (bypass(event.getPlayer()) || !regions.isProtected(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    /** Boats, minecarts, armor stands, end crystals and everything else placed as an entity. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (bypass(event.getPlayer()) || !regions.isProtected(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    // ---------------------------------------------------------------- explosions and fire

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        // Bosses and mobs may explode, but they must never damage the fortress.
        event.blockList().removeIf(regions::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(regions::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!regions.isProtected(event.getBlock())) {
            return;
        }
        if (event.getEntity() instanceof Player player && bypass(player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (regions.isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (!regions.isProtected(event.getBlock())) {
            return;
        }
        if (bypass(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (regions.isProtected(event.getBlock()) || regions.isProtected(event.getSource())) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- pistons and fluids

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (crossesBoundary(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (crossesBoundary(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    /**
     * A piston action is allowed only when the piston, every moved block and every destination
     * belong to the same region - or to no region at all. Anything else would move blocks across
     * the outpost border.
     */
    private boolean crossesBoundary(Block piston, List<Block> moved, BlockFace direction) {
        Optional<OutpostDefinition> reference = regions.at(piston.getLocation());
        for (Block block : moved) {
            if (!regions.at(block.getLocation()).equals(reference)) {
                return true;
            }
            if (!regions.at(block.getRelative(direction).getLocation()).equals(reference)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (regions.crossesBoundary(event.getBlock().getLocation(), event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- hanging entities

    /**
     * Also receives {@link HangingBreakByEntityEvent}, so the admin bypass is applied in one place: a
     * general handler that cancelled regardless would undo the exception the by-entity rule grants.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (!regions.isProtected(event.getEntity().getLocation()) || removedByAdmin(event)) {
            return;
        }
        event.setCancelled(true);
    }

    private boolean removedByAdmin(HangingBreakEvent event) {
        return event instanceof HangingBreakByEntityEvent byEntity
                && resolveActor(byEntity.getRemover()) instanceof Player player && bypass(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (bypass(event.getPlayer()) || !regions.isProtected(event.getEntity().getLocation())) {
            return;
        }
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    /** Item frame rotation, armor stand equipment and similar direct entity manipulation. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        if (bypass(event.getPlayer()) || !regions.isProtected(target.getLocation())) {
            return;
        }
        if (isDecorative(target)) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    // ---------------------------------------------------------------- containers

    /**
     * Containers inside a region stay closed.
     *
     * <p>The only exception is a configured loot container of the active outpost that has been
     * unlocked for the current run <b>and</b> whose owning town the player belongs to. Unlocked is
     * not the same as public: townless players and foreign towns never get the winner's loot.
     *
     * <p>"Active outpost" means the exact geometry the run was started with. After a reload moved the
     * running outpost, a container at the new position shares the id and the container name but not
     * the run, so it stays locked until that geometry is actually used.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || bypass(player)) {
            return;
        }
        // Resolved through the holder: both halves of a double chest are considered, and
        // inventories without a usable location simply fall through.
        List<Location> blocks = InventoryLocations.blocksOf(event.getInventory());
        Optional<OutpostDefinition> outpost = blocks.stream()
                .map(regions::at)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
        if (outpost.isEmpty()) {
            return;
        }
        Optional<String> containerName = blocks.stream()
                .map(block -> LootService.containerNameAt(outpost.get(), block.getBlockX(),
                        block.getBlockY(), block.getBlockZ()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
        if (containerName.isEmpty()) {
            event.setCancelled(true);
            ui.send(player, UiKeys.REGION_PROTECTED, new UiTokens());
            return;
        }
        if (!engine.isActiveGeometry(outpost.get())
                || !engine.context().loot().isUnlocked(outpost.get().id(), containerName.get())) {
            event.setCancelled(true);
            ui.send(player, UiKeys.CONTAINER_LOCKED, new UiTokens());
            return;
        }
        if (!engine.mayOpenLoot(player)) {
            event.setCancelled(true);
            ui.send(player, UiKeys.CONTAINER_NOT_YOURS,
                    UiTokens.of("town", engine.state().controllingTownName().isBlank()
                            ? "—" : engine.state().controllingTownName()));
        }
    }

    /**
     * Blocks automated inventory transfer touching a protected container - hoppers, hopper
     * minecarts and droppers, including transfers that cross the region border in either
     * direction.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (isProtectedInventory(event.getSource()) || isProtectedInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event) {
        if (isProtectedInventory(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    private boolean isProtectedInventory(Inventory inventory) {
        return InventoryLocations.blocksOf(inventory).stream().anyMatch(regions::isProtected);
    }

    // ---------------------------------------------------------------- decorative entities

    /**
     * Item frames and armor stands are protected against damage too, not just against a right
     * click. Removing an item from a frame or breaking a stand arrives as a damage event, and a
     * projectile or an explosion can do it from a distance.
     */
    /**
     * Also receives {@link EntityDamageByEntityEvent} and the by-block variant, so every damage source -
     * a hit, a projectile, an explosion, fire - is judged by the same rule, and an administrator with
     * the bypass permission really can take a frame or a stand down.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!isDecorative(event.getEntity()) || !regions.isProtected(event.getEntity().getLocation())) {
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity
                && resolveActor(byEntity.getDamager()) instanceof Player player && bypass(player)) {
            return;
        }
        event.setCancelled(true);
    }

    /** Armor stand equipment may not be taken, swapped or added. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (bypass(event.getPlayer()) || !regions.isProtected(event.getRightClicked().getLocation())) {
            return;
        }
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    private boolean isDecorative(Entity entity) {
        return entity instanceof Hanging || entity instanceof ArmorStand;
    }

    private Entity resolveActor(Entity damager) {
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }
}
