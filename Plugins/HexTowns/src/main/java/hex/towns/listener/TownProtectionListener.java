package hex.towns.listener;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.towns.api.TownPermission;
import hex.towns.config.TownsConfig;
import hex.towns.model.Town;
import hex.towns.model.TownStatus;
import hex.towns.service.TownsService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Core claim protection owned by HexTowns. */
public final class TownProtectionListener implements Listener {
    private static final long DENY_MESSAGE_COOLDOWN_MS = 1500L;

    private final HexApi api;
    private final TownsService service;
    private final TownsConfig config;
    private final ConcurrentMap<UUID, Long> nextDenyMessageAt = new ConcurrentHashMap<>();

    public TownProtectionListener(HexApi api, TownsService service, TownsConfig config) {
        this.api = api;
        this.service = service;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isDestroyLocked(event.getBlock().getLocation()) || (config.protectionBlockBreak() && !allowBuild(event.getPlayer(), event.getBlock().getLocation(), TownPermission.BREAK))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isDestroyLocked(event.getBlock().getLocation()) || (config.protectionBlockPlace() && !allowBuild(event.getPlayer(), event.getBlock().getLocation(), TownPermission.BUILD))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) return;
        Location location = block.getLocation();
        if (isDestroyLocked(location)) {
            event.setCancelled(true);
            sendDenyMessage(event.getPlayer(), service.protectedTownAt(location).orElse(null));
            return;
        }
        Material type = block.getType();
        boolean container = block.getState() instanceof InventoryHolder;
        boolean door = isDoorLike(type);
        boolean switchLike = isSwitchLike(type);
        if (container && config.protectionInteractContainers() && !allowClaimInteraction(event.getPlayer(), location, TownPermission.CONTAINERS)) {
            event.setCancelled(true);
        } else if (door && config.protectionInteractDoors() && !allowMemberInteraction(event.getPlayer(), location)) {
            event.setCancelled(true);
        } else if (switchLike && config.protectionInteractSwitches() && !allowMemberInteraction(event.getPlayer(), location)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Location location = event.getInventory().getLocation();
        if (location == null) return;
        // Block containers are already guarded by PlayerInteractEvent; this additionally
        // protects entity inventories such as chest/hopper minecarts.
        if (isDestroyLocked(location) || (config.protectionInteractContainers() && !allowClaimInteraction(player, location, TownPermission.CONTAINERS))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Location source = event.getSource().getLocation();
        Location destination = event.getDestination().getLocation();
        if (source == null || destination == null) return;
        Optional<UUID> sourceTown = service.protectedTownAt(source).map(Town::id);
        Optional<UUID> destinationTown = service.protectedTownAt(destination).map(Town::id);
        if (!sourceTown.equals(destinationTown) && (sourceTown.isPresent() || destinationTown.isPresent())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!allowBuild(event.getPlayer(), event.getBlock().getLocation(), TownPermission.BUILD)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!allowBuild(event.getPlayer(), event.getBlock().getLocation(), TownPermission.BREAK)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Optional<Town> town = service.protectedTownAt(event.getItem().getLocation());
        if (town.isEmpty()) return;
        Town protectedTown = town.get();
        if (protectedTown.status() != TownStatus.ACTIVE) {
            event.setCancelled(true);
            return;
        }
        if (service.canActAsMember(player.getUniqueId(), protectedTown.id())) return;
        long protectedTicks = Math.max(0L, config.itemPickupWindowSeconds()) * 20L;
        if (event.getItem().getTicksLived() < protectedTicks) {
            event.setCancelled(true);
            sendDenyMessage(player, protectedTown);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block moved : event.getBlocks()) {
            if (crossesTownBoundary(moved.getLocation(), moved.getRelative(event.getDirection()).getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block moved : event.getBlocks()) {
            if (crossesTownBoundary(moved.getLocation(), moved.getRelative(event.getDirection()).getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (crossesTownBoundary(event.getBlock().getLocation(), event.getToBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Optional<Town> town = service.protectedTownAt(event.getBlock().getLocation());
        if (town.isEmpty()) return;
        if (town.get().status() != TownStatus.ACTIVE) {
            event.setCancelled(true);
            return;
        }
        if (!config.mobBlockChangesEnabled()) return;
        if (config.blockedMobBlockChangeEntities().contains(event.getEntityType().name())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player attacker = attackingPlayer(event.getDamager());

        if (event.getEntity() instanceof Player victim) {
            if (attacker != null && attacker.getUniqueId().equals(victim.getUniqueId())) return;
            Optional<Town> town = service.protectedTownAt(victim.getLocation());
            if (town.isPresent() && attacker != null && !config.protectionPvp()) event.setCancelled(true);
            return;
        }

        if (attacker == null || !config.protectionMobs() || !(event.getEntity() instanceof LivingEntity living)) return;
        Optional<Town> town = service.protectedTownAt(living.getLocation());
        if (town.isEmpty()) return;
        if (town.get().status() == TownStatus.ACTIVE && service.canActAsMember(attacker.getUniqueId(), town.get().id())) return;

        if (!(living instanceof Monster) || living instanceof Animals || living instanceof Tameable) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!config.protectionExplosion()) return;
        event.blockList().removeIf(block -> service.protectedTownAt(block.getLocation()).isPresent());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!config.protectionExplosion()) return;
        event.blockList().removeIf(block -> service.protectedTownAt(block.getLocation()).isPresent());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Optional<Town> protectedTown = service.protectedTownAt(event.getBlock().getLocation());
        if (protectedTown.isEmpty()) return;

        Town town = protectedTown.get();
        if (town.status() != TownStatus.ACTIVE) {
            event.setCancelled(true);
            return;
        }

        // Manual/player-caused ignition is an access action, not membership identity. Respect
        // BUILD permission (owner/member) and the administrative bypass, while keeping natural
        // spread/burn protection independent. This allows e.g. flint and steel in one's own town
        // without turning allow-fire-spread on for lava/SPREAD/other uncontrolled sources.
        Player ignitingPlayer = event.getPlayer();
        if (ignitingPlayer == null) ignitingPlayer = attackingPlayer(event.getIgnitingEntity());
        if (config.protectionAllowMemberIgnite()
                && ignitingPlayer != null
                && service.can(ignitingPlayer.getUniqueId(), town.id(), TownPermission.BUILD)) {
            return;
        }

        if (!config.protectionAllowFireSpread()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (config.protectionAllowFireSpread()) return;
        if (service.protectedTownAt(event.getBlock().getLocation()).isPresent()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (config.protectionAllowFireSpread() || event.getSource().getType() != Material.FIRE) return;
        if (service.protectedTownAt(event.getBlock().getLocation()).isPresent()) event.setCancelled(true);
    }

    private Player attackingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        if (damager instanceof Tameable tameable && tameable.getOwner() instanceof Player player) return player;
        if (damager instanceof TNTPrimed tnt) {
            Entity source = tnt.getSource();
            if (source instanceof Player player) return player;
            if (source instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        }
        return null;
    }

    private boolean allowBuild(Player player, Location location, TownPermission permission) {
        Optional<Town> direct = service.protectedTownAt(location);
        if (direct.isPresent()) {
            Town town = direct.get();
            if (town.status() != TownStatus.ACTIVE) {
                sendDenyMessage(player, town);
                return false;
            }
            if (!service.can(player.getUniqueId(), town.id(), permission)) {
                sendDenyMessage(player, town);
                return false;
            }
            return true;
        }
        Optional<Town> blockingTown = service.blockingTownForBuild(player.getUniqueId(), location);
        if (blockingTown.isEmpty()) return true;
        sendDenyMessage(player, blockingTown.get());
        return false;
    }

    private boolean allowClaimInteraction(Player player, Location location, TownPermission permission) {
        Optional<Town> town = service.protectedTownAt(location);
        if (town.isEmpty()) return true;
        if (town.get().status() != TownStatus.ACTIVE) {
            sendDenyMessage(player, town.get());
            return false;
        }
        if (!service.can(player.getUniqueId(), town.get().id(), permission)) {
            sendDenyMessage(player, town.get());
            return false;
        }
        return true;
    }

    private boolean allowMemberInteraction(Player player, Location location) {
        Optional<Town> town = service.protectedTownAt(location);
        if (town.isEmpty()) return true;
        if (town.get().status() != TownStatus.ACTIVE || !service.canActAsMember(player.getUniqueId(), town.get().id())) {
            sendDenyMessage(player, town.get());
            return false;
        }
        return true;
    }

    private boolean crossesTownBoundary(Location from, Location to) {
        Optional<UUID> a = service.protectedTownAt(from).map(Town::id);
        Optional<UUID> b = service.protectedTownAt(to).map(Town::id);
        return !a.equals(b) && (a.isPresent() || b.isPresent());
    }

    private boolean isDestroyLocked(Location location) {
        return service.protectedTownAt(location).map(town -> town.status() != TownStatus.ACTIVE).orElse(false);
    }

    private static boolean isDoorLike(Material material) {
        String name = material.name();
        return name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR") || name.endsWith("_FENCE_GATE");
    }

    private static boolean isSwitchLike(Material material) {
        String name = material.name();
        return name.endsWith("_BUTTON") || name.equals("LEVER");
    }

    private void sendDenyMessage(Player player, Town town) {
        if (player == null || town == null) return;
        long now = System.currentTimeMillis();
        Long next = nextDenyMessageAt.get(player.getUniqueId());
        if (next != null && now < next) return;
        nextDenyMessageAt.put(player.getUniqueId(), now + DENY_MESSAGE_COOLDOWN_MS);
        api.ui().send(player, "towns.protect.no-build", UiTokens.of("town", town.name()));
    }
}
