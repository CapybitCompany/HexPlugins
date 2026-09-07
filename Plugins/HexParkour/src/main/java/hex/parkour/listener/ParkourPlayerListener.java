package hex.parkour.listener;

import hex.parkour.model.ParkourPlayerSession;
import hex.parkour.service.ParkourItemService;
import hex.parkour.service.ParkourItemType;
import hex.parkour.service.ParkourSessionService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

public final class ParkourPlayerListener implements Listener {
    private final ParkourSessionService sessions;
    private final ParkourItemService items;

    public ParkourPlayerListener(ParkourSessionService sessions, ParkourItemService items) {
        this.sessions = sessions;
        this.items = items;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        sessions.handlePendingJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sessions.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        sessions.handleWorldChange(event.getPlayer(), event.getFrom());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        sessions.handleMove(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ParkourPlayerSession session = sessions.ensureParkourWorldSession(player);
        if (canBuildInParkour(player)) return;
        ItemStack item = event.getItem();
        ParkourItemType type = session == null ? null : items.type(item, player.getInventory().getHeldItemSlot(), session, sessions.config());
        if (type == null) {
            if (inParkourWorld(player) && items.couldBeParkourControl(item, sessions.config())) {
                cancelItemUse(event);
            }
            return;
        }
        cancelItemUse(event);
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        switch (type) {
            case VISIBILITY -> sessions.toggleVisibility(player, session);
            case LEAVE_PARKOUR -> sessions.requestPlayerLeave(player);
            case RESET_CHECKPOINT -> sessions.resetToCheckpoint(player, session, "reset");
            case RETURN_LOBBY -> sessions.enterLobby(player, session);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!protectedParkourPlayer(player)) return;
        event.setCancelled(true);
        player.setFallDistance(0.0f);
        player.setFireTicks(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attackingPlayer(event.getDamager());
        if (attacker == null) return;
        if (!protectedParkourPlayer(victim) && !protectedParkourPlayer(attacker)) return;
        event.setCancelled(true);
        victim.setFallDistance(0.0f);
        attacker.setFallDistance(0.0f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (canBuildInParkour(player)) return;
        if (sessions.active(player) || inParkourWorld(player) || items.isParkourItem(event.getCurrentItem()) || items.isParkourItem(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && !canBuildInParkour(player) && (sessions.active(player) || inParkourWorld(player))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (canBuildInParkour(event.getPlayer())) return;
        if (sessions.active(event.getPlayer()) || inParkourWorld(event.getPlayer()) || items.isParkourItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && canBuildInParkour(player)) return;
        if (event.getEntity() instanceof Player player && inParkourWorld(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (canBuildInParkour(event.getPlayer())) return;
        if (sessions.active(event.getPlayer()) || inParkourWorld(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (canBuildInParkour(event.getPlayer())) return;
        if (sessions.active(event.getPlayer()) || inParkourWorld(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!isParkourWorld(event.getFrom())) return;
        event.setCancelled(true);
        if (!sessions.handlePortalTrigger(event.getPlayer(), event.getFrom())) {
            sessions.handlePortalTrigger(event.getPlayer(), event.getPlayer().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (isParkourWorld(event.getFrom())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!event.isCancelled() && sessions.config() != null && event.getTo() != null && event.getTo().getWorld() != null
                && event.getTo().getWorld().getName().equals(sessions.config().worldName())
                && !isParkourWorld(event.getFrom())) {
            if (!sessions.prepareAutoWorldJoin(event.getPlayer(), event.getFrom(), event.getTo())) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL && isParkourWorld(event.getFrom())) {
            event.setCancelled(true);
            if (!sessions.handlePortalTrigger(event.getPlayer(), event.getFrom())) {
                sessions.handlePortalTrigger(event.getPlayer(), event.getPlayer().getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!sessions.active(player)) return;
        event.setKeepInventory(true);
        event.getDrops().removeIf(items::isParkourItem);
        event.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        ParkourPlayerSession session = sessions.session(player);
        if (session == null || sessions.config() == null) return;
        if (session.currentArenaId() == null) {
            var lobby = sessions.lobbySpawnLocation();
            if (lobby != null) event.setRespawnLocation(lobby);
            return;
        }
        var arena = sessions.config().arenas().get(session.currentArenaId());
        if (arena != null) {
            var target = sessions.currentResetLocation(session);
            if (target != null) event.setRespawnLocation(target);
        }
    }

    private boolean inParkourWorld(Player player) {
        return sessions.config() != null
                && player.getWorld().getName().equals(sessions.config().worldName())
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    private boolean protectedParkourPlayer(Player player) {
        return sessions.active(player) || inParkourWorld(player);
    }

    private boolean canBuildInParkour(Player player) {
        return player.getGameMode() == GameMode.CREATIVE
                && (player.isOp() || player.hasPermission("hexparkour.build"));
    }

    private boolean isParkourWorld(Location location) {
        return sessions.config() != null
                && location != null
                && location.getWorld() != null
                && location.getWorld().getName().equals(sessions.config().worldName());
    }

    private void cancelItemUse(PlayerInteractEvent event) {
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
    }

    private Player attackingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }
}
