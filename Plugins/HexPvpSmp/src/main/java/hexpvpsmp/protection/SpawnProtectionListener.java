package hexpvpsmp.protection;

import hexpvpsmp.HexPvpSmpPlugin;
import hexpvpsmp.combat.PermissionGate;
import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.config.SpawnConfig;
import hexpvpsmp.config.WorldConfig;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;
import java.util.Objects;

/**
 * Config-driven spawn protection. Replaces WorldGuard for the spawn area.
 *
 * <p>Building/breaking is denied in every region ({@code SPAWN_SAFEZONE} and
 * {@code NO_BUILD}); mob spawns are denied only in {@code SPAWN_SAFEZONE} when
 * {@code block-mob-spawns} is on. Environmental griefing (liquids, fire spread,
 * explosions, pistons) is stopped at region boundaries. Players with
 * {@code hexpvpsmp.bypass} (or OP) are exempt from the player-driven checks.
 *
 * <p>Public chests are an explicit per-block allowlist: players may open/use
 * them even inside the safezone, but they can never be broken, blown up or
 * shoved by a piston.
 *
 * <p><b>Entity interaction:</b> this listener only inspects block interaction
 * ({@link PlayerInteractEvent} on containers). It never blocks entity
 * interaction, so NPC clicks (HexNPC drives those via PacketEvents
 * {@code INTERACT_ENTITY}, not Bukkit events) are unaffected. Plugin-spawned
 * entities also survive here because {@code SpawnReason.CUSTOM} is exempt from
 * the mob-spawn block.
 */
public final class SpawnProtectionListener implements Listener {

    private final HexPvpSmpPlugin plugin;

    public SpawnProtectionListener(HexPvpSmpPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    // ---- Player building -------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isEnabled() || PermissionGate.bypasses(event.getPlayer())) {
            return;
        }
        if (isBuildProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
            denyBuild(event.getPlayer(), "place");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isEnabled() || PermissionGate.bypasses(event.getPlayer())) {
            return;
        }
        Block block = event.getBlock();
        if (isBuildProtected(block.getLocation()) || isPublicChest(block)) {
            event.setCancelled(true);
            denyBuild(event.getPlayer(), "break");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        handleBucket(event.getPlayer(), event.getBlockClicked().getRelative(event.getBlockFace()).getLocation(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        handleBucket(event.getPlayer(), event.getBlockClicked().getLocation(), event);
    }

    private void handleBucket(Player player, Location target, org.bukkit.event.Cancellable event) {
        if (!isEnabled() || PermissionGate.bypasses(player)) {
            return;
        }
        if (isBuildProtected(target)) {
            event.setCancelled(true);
            denyBuild(player, "bucket");
        }
    }

    // ---- Container interaction (public-chest allowlist) ------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !isEnabled() || PermissionGate.bypasses(event.getPlayer())) {
            return;
        }
        if (isPublicChest(block)) {
            return; // explicitly allowed, even inside spawn
        }
        if (!isBuildProtected(block.getLocation())) {
            return;
        }
        // Block container access inside protected regions so the spawn can't be
        // used as private/shared storage that bypasses the build protection.
        BlockState state = block.getState();
        if (state instanceof InventoryHolder) {
            event.setCancelled(true);
            denyBuild(event.getPlayer(), "interact");
        }
    }

    // ---- Environmental griefing -----------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent event) {
        if (!isEnabled()) {
            return;
        }
        // Protect against liquids / dragon-egg flowing INTO a protected area.
        if (isBuildProtected(event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isEnabled() && isBuildProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (isEnabled() && isBuildProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (isEnabled() && isBuildProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        // Endermen picking up blocks, etc.
        if (isEnabled() && isBuildProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (isEnabled()) {
            protectFromExplosion(event.blockList());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (isEnabled()) {
            protectFromExplosion(event.blockList());
        }
    }

    private void protectFromExplosion(List<Block> blocks) {
        blocks.removeIf(b -> isBuildProtected(b.getLocation()) || isPublicChest(b));
    }

    // ---- Pistons ---------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (isEnabled() && pistonTouchesProtected(event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        // On retract the pulled blocks travel toward the piston, i.e. opposite
        // to the reported facing direction.
        if (isEnabled() && pistonTouchesProtected(event.getBlocks(), event.getDirection().getOppositeFace())) {
            event.setCancelled(true);
        }
    }

    /**
     * A piston move is denied when any moved block — or its destination — is
     * build-protected or a public chest. This stops pistons pushing blocks into,
     * or pulling blocks out of / into, a protected region (and prevents machines
     * shoving a public chest).
     *
     * @param moveDirection the direction each moved block actually travels:
     *                      the piston facing for extend, its opposite for retract.
     */
    private boolean pistonTouchesProtected(List<Block> blocks, BlockFace moveDirection) {
        for (Block block : blocks) {
            if (isBuildProtected(block.getLocation()) || isPublicChest(block)) {
                return true;
            }
            Block destination = block.getRelative(moveDirection);
            if (isBuildProtected(destination.getLocation()) || isPublicChest(destination)) {
                return true;
            }
        }
        return false;
    }

    // ---- Mob spawns ------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!isEnabled()) {
            return;
        }
        // Let plugins (NPCs, quest mobs) place custom entities; deny everything else.
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            return;
        }
        Location at = event.getLocation();
        if (isMobSpawnBlocked(at)) {
            event.setCancelled(true);
            plugin.debugLog(String.format("Mob spawn blocked: %s (%s) at %s %.0f,%.0f,%.0f",
                    event.getEntityType(), event.getSpawnReason(),
                    at.getWorld() != null ? at.getWorld().getName() : "?",
                    at.getX(), at.getY(), at.getZ()));
        }
    }

    // ---- Helpers ---------------------------------------------------------

    private boolean isEnabled() {
        HexPvpConfig config = plugin.config();
        return config != null && config.enabled();
    }

    private boolean isBuildProtected(Location location) {
        ProtectionService protection = plugin.protectionService();
        return protection != null && protection.isBuildProtected(location);
    }

    private boolean isPublicChest(Block block) {
        if (block == null || block.getWorld() == null) {
            return false;
        }
        PublicChestRegistry registry = plugin.publicChestRegistry();
        return registry != null && registry.isPublicChest(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private boolean isMobSpawnBlocked(Location loc) {
        HexPvpConfig config = plugin.config();
        if (config == null || loc == null || loc.getWorld() == null) {
            return false;
        }
        WorldConfig world = config.world(loc.getWorld().getName()).orElse(null);
        if (world == null || !world.enabled()) {
            return false;
        }
        SpawnConfig spawn = world.spawn();
        return spawn.enabled() && spawn.blockMobSpawns() && spawn.region() != null
                && spawn.region().contains(loc.getX(), loc.getZ());
    }

    private void denyBuild(Player player, String action) {
        HexPvpConfig config = plugin.config();
        if (config != null) {
            plugin.messageService().sendChat(player, config.messages().buildDenied());
        }
        plugin.debugLog("Build denied (" + action + ") for " + player.getName());
    }
}
