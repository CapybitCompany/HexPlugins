package hex.endevent.listener;

import hex.endevent.service.EndEventService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EndAccessListener implements Listener {
    private final Plugin plugin;
    private final EndEventService service;
    private final Map<UUID, Long> nextMessageAt = new HashMap<>();

    public EndAccessListener(Plugin plugin, EndEventService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) return;
        Player player = event.getPlayer();
        // END_PORTAL uzyty w Endzie sluzy do wyjscia i nigdy nie powinien byc blokowany.
        if (player.getWorld().getEnvironment() == World.Environment.THE_END) return;
        World target = event.getTo() == null ? null : event.getTo().getWorld();
        boolean targetLooksLikeEnd = target == null || target.getEnvironment() == World.Environment.THE_END;
        if (!targetLooksLikeEnd) return;
        if (target != null && !service.shouldProtectTarget(target)) return;
        if (target == null ? !service.canEnterEnd(player) : !service.canEnter(player, target)) {
            event.setCancelled(true);
            notifyBlocked(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event instanceof PlayerPortalEvent) return;
        if (event.getTo() == null) return;
        World target = event.getTo().getWorld();
        if (!service.shouldProtectTarget(target)) return;
        if (service.canEnter(event.getPlayer(), target)) return;
        event.setCancelled(true);
        notifyBlocked(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            service.enforcePlayer(player, true);
            service.refreshBossBar(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            service.enforcePlayer(player, true);
            service.refreshBossBar(player);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        World target = event.getRespawnLocation().getWorld();
        if (!service.shouldProtectTarget(target)) return;
        if (service.canEnter(event.getPlayer(), target)) return;
        var returnLocation = Bukkit.getWorld(service.config().returnWorld());
        if (returnLocation != null && returnLocation.getEnvironment() == World.Environment.NORMAL) {
            event.setRespawnLocation(returnLocation.getSpawnLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        nextMessageAt.remove(event.getPlayer().getUniqueId());
        service.hideBossBar(event.getPlayer());
    }

    private void notifyBlocked(Player player) {
        long now = System.currentTimeMillis();
        long allowedAt = nextMessageAt.getOrDefault(player.getUniqueId(), 0L);
        if (now < allowedAt) return;
        nextMessageAt.put(player.getUniqueId(), now + service.config().blockedMessageCooldown().toMillis());
        service.notifyBlocked(player);
    }
}
