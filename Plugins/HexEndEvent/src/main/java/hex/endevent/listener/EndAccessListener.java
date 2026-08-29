package hex.endevent.listener;

import hex.endevent.service.EndEventService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EndAccessListener implements Listener {
    private final Plugin plugin; private final EndEventService service; private final Map<UUID,Long> nextMessageAt=new HashMap<>();
    public EndAccessListener(Plugin plugin,EndEventService service){this.plugin=plugin;this.service=service;}

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onPortal(PlayerPortalEvent event){
        if(event.getCause()!=PlayerTeleportEvent.TeleportCause.END_PORTAL)return;Player player=event.getPlayer();if(player.getWorld().getEnvironment()==World.Environment.THE_END)return;
        World target=event.getTo()==null?null:event.getTo().getWorld();if(target!=null&&!service.shouldProtectTarget(target))return;
        if(service.consumeAuthorizedTeleport(player))return;
        if(player.hasPermission(service.config().bypassPermission())&&service.canEnterEnd(player))return;
        event.setCancelled(true);service.requestJoin(player,"WORLD_PORTAL");
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onTeleport(PlayerTeleportEvent event){
        if(event instanceof PlayerPortalEvent||event.getTo()==null)return;World target=event.getTo().getWorld();if(!service.shouldProtectTarget(target))return;
        if(service.consumeAuthorizedTeleport(event.getPlayer()))return;
        if(event.getPlayer().hasPermission(service.config().bypassPermission())&&service.canEnterEnd(event.getPlayer()))return;
        event.setCancelled(true);service.requestJoin(event.getPlayer(),"COMMAND");
    }
    @EventHandler(priority=EventPriority.MONITOR) public void onChangedWorld(PlayerChangedWorldEvent event){Player p=event.getPlayer();Bukkit.getScheduler().runTask(plugin,()->{service.enforcePlayer(p,true);service.refreshBossBar(p);});}
    @EventHandler(priority=EventPriority.MONITOR) public void onJoin(PlayerJoinEvent event){Player p=event.getPlayer();Bukkit.getScheduler().runTask(plugin,()->{service.enforcePlayer(p,true);service.refreshBossBar(p);});}
    @EventHandler(priority=EventPriority.HIGHEST) public void onRespawn(PlayerRespawnEvent event){World target=event.getRespawnLocation().getWorld();if(!service.shouldProtectTarget(target)||service.canEnter(event.getPlayer(),target))return;World ret=Bukkit.getWorld(service.config().returnWorld());if(ret!=null&&ret.getEnvironment()==World.Environment.NORMAL)event.setRespawnLocation(ret.getSpawnLocation());}
    @EventHandler(priority=EventPriority.MONITOR) public void onQuit(PlayerQuitEvent event){nextMessageAt.remove(event.getPlayer().getUniqueId());service.hideBossBar(event.getPlayer());}
}
