package hex.bossfight.listener;

import hex.bossfight.fight.*;
import hex.events.api.HexEventsApi;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.time.Instant;

public final class BossCombatListener implements Listener {
    private final ActiveFightRegistry fights; private final HexEventsApi events; private final FightResultBuilder results=new FightResultBuilder(); private final boolean projectiles;
    public BossCombatListener(ActiveFightRegistry fights,HexEventsApi events,boolean projectiles){this.fights=fights;this.events=events;this.projectiles=projectiles;}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onDamage(EntityDamageByEntityEvent event){ActiveBossFight f=fights.byEntity(event.getEntity().getUniqueId()).orElse(null);if(f==null)return;Player p=player(event.getDamager());if(p==null||!f.participants.contains(p.getUniqueId()))return;PlayerBossStats s=f.stats(p.getUniqueId());s.damage+=Math.max(0,event.getFinalDamage());s.hits++;}
    @EventHandler(priority=EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event){ActiveBossFight f=fights.byEntity(event.getEntity().getUniqueId()).orElse(null);if(f==null)return;fights.unbindEntity(f);if(!events.complete(f.instanceId,results.success(f,Instant.now())))events.fail(f.instanceId,new hex.events.api.EventFailure("BOSS_COMPLETION_REJECTED","HexEvents rejected boss completion",false));}
    @EventHandler(priority=EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event){for(ActiveBossFight f:fights.all())if(f.participants.contains(event.getEntity().getUniqueId()))f.stats(event.getEntity().getUniqueId()).deaths++;}
    private Player player(Entity damager){if(damager instanceof Player p)return p;if(projectiles&&damager instanceof Projectile projectile){ProjectileSource source=projectile.getShooter();if(source instanceof Player p)return p;}return null;}
}
