package hex.minigames.listener;

import hex.minigames.runtime.MinigamesSessionService;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

public final class MinigamesEventRouter implements Listener {
    private final MinigamesSessionService sessions;

    public MinigamesEventRouter(MinigamesSessionService sessions) {
        this.sessions = sessions;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInput(org.bukkit.event.player.PlayerInputEvent event) { sessions.routeInput(event); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) { sessions.routeInventoryClose(event); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHeldSlot(org.bukkit.event.player.PlayerItemHeldEvent event) { sessions.routeHeldSlot(event); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onToggleFlight(org.bukkit.event.player.PlayerToggleFlightEvent event) { sessions.routeToggleFlight(event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreAttack(io.papermc.paper.event.player.PrePlayerAttackEntityEvent event) {
        sessions.routePreAttack(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onJump(com.destroystokyo.paper.event.player.PlayerJumpEvent event) { sessions.routeJump(event); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKnockback(io.papermc.paper.event.entity.EntityKnockbackEvent event) { sessions.routeKnockback(event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        sessions.handleCommand(event);
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
        sessions.handleChangedWorld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        sessions.handleMove(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        sessions.handleTeleport(event);
        sessions.captureLobbyEntry(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(io.papermc.paper.event.packet.PlayerChunkLoadEvent event) {
        sessions.handleChunkLoad(event.getPlayer(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        sessions.handleChat(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        sessions.routeInteract(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        sessions.routeBlockBreak(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        sessions.routeBlockDamage(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        sessions.routeBlockPlace(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) return;
        if (event.getEntity() instanceof Player player) {
            sessions.routeDamage(player, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        sessions.routeEntityExplode(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim) {
            if (sessions.routeDamage(victim, event)) return;
        }
        Player attacker = event.getDamager() instanceof Player direct ? direct
                : event.getDamager() instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player shooter ? shooter : null;
        if (attacker != null && sessions.activeSessionContains(attacker.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        sessions.routeDrop(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && sessions.activeSessionContains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            sessions.routeInventoryClick(event, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            sessions.routeInventoryDrag(event, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (sessions.activeSessionContains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        sessions.routeToggleSneak(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && sessions.activeSessionContains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRegainHealth(org.bukkit.event.entity.EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player) sessions.routeRegainHealth(player, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!sessions.activeSessionContains(player.getUniqueId())) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

}
