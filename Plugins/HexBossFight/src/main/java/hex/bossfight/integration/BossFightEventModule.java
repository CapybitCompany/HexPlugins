package hex.bossfight.integration;

import hex.bossfight.engine.*;
import hex.bossfight.fight.*;
import hex.events.api.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class BossFightEventModule implements HexEventModule {
    private final BossEngineAdapter adapter; private final ActiveFightRegistry fights; private final boolean strictSchedules, strictRewards;
    public BossFightEventModule(BossEngineAdapter adapter,ActiveFightRegistry fights,boolean strictSchedules,boolean strictRewards){this.adapter=adapter;this.fights=fights;this.strictSchedules=strictSchedules;this.strictRewards=strictRewards;}
    @Override public String moduleId(){return "hex:boss";}
    @Override public EventModuleCapabilities capabilities(){return new EventModuleCapabilities(true,true,true,true,false,true,false,false);}
    @Override public EventAvailability availability(EventModuleSettings settings){return health(settings).ready()?EventAvailability.AVAILABLE:(adapter.health().status()==AdapterHealth.Status.DEPENDENCY_UNAVAILABLE?EventAvailability.DEPENDENCY_UNAVAILABLE:EventAvailability.MISCONFIGURED);}
    @Override public String availabilityReason(EventModuleSettings settings){return health(settings).message();}
    private AdapterHealth health(EventModuleSettings s){String provider=s.string("provider","stormbossy");if(!"stormbossy".equalsIgnoreCase(provider))return new AdapterHealth(AdapterHealth.Status.MISCONFIGURED,"Unsupported provider: "+provider);return adapter.validateBoss(s.string("boss-id",""),s.integer("spawn-location",1),strictSchedules,strictRewards);}
    @Override public CompletionStage<PrepareResult> prepare(EventExecutionContext context){AdapterHealth h=health(context.settings());if(!h.ready())return CompletableFuture.completedFuture(PrepareResult.failed(h.message()));ActiveBossFight f=new ActiveBossFight(context,context.settings().string("boss-id",""),context.settings().integer("spawn-location",1));fights.put(f);return CompletableFuture.completedFuture(PrepareResult.ok());}
    @Override public CompletionStage<StartResult> start(EventExecutionContext context){AdapterHealth strictHealth=health(context.settings());if(!strictHealth.ready())return CompletableFuture.completedFuture(StartResult.failed(strictHealth.message()));ActiveBossFight f=fights.byInstance(context.instanceId()).orElseGet(()->{ActiveBossFight x=new ActiveBossFight(context,context.settings().string("boss-id",""),context.settings().integer("spawn-location",1));fights.put(x);return x;});BossSpawnResult r=adapter.spawn(f.bossId,f.spawnLocation);if(!r.success())return CompletableFuture.completedFuture(StartResult.failed(r.message()));fights.bindEntity(f,r.entityId());f.startedAt=Instant.now();for(UUID p:f.participants)f.stats(p).join(f.startedAt);return CompletableFuture.completedFuture(StartResult.started());}
    @Override public EventJoinResult join(EventJoinRequest request){Player p=Bukkit.getPlayer(request.playerId());if(p==null||!p.isOnline())return EventJoinResult.denied("Gracz offline");ActiveBossFight f=fights.byInstance(request.instanceId()).orElseGet(()->{ActiveBossFight x=new ActiveBossFight(request.context(),request.context().settings().string("boss-id",""),request.context().settings().integer("spawn-location",1));fights.put(x);return x;});f.participants.add(p.getUniqueId());if(f.startedAt!=null)f.stats(p.getUniqueId()).join(Instant.now());Location target=parseLocation(request.context().settings(),"lobby-location");if(target==null)target=adapter.spawnLocation(f.bossId,f.spawnLocation).orElse(null);if(target!=null)p.teleport(target);return EventJoinResult.joined();}
    @Override public void leave(UUID instanceId,UUID playerId,LeaveReason reason){fights.byInstance(instanceId).ifPresent(f->{f.stats(playerId).leave(Instant.now());f.participants.remove(playerId);});}
    @Override public CompletionStage<StopResult> stop(UUID instanceId,EventStopReason reason){ActiveBossFight f=fights.remove(instanceId);if(f==null)return CompletableFuture.completedFuture(StopResult.stopped());if(f.bossEntityId!=null){StopBossResult r=adapter.stop(f.bossEntityId);if(!r.success())return CompletableFuture.completedFuture(StopResult.failed(r.message()));}return CompletableFuture.completedFuture(StopResult.stopped());}
    @Override public EventRuntimeSnapshot snapshot(UUID instanceId){return fights.byInstance(instanceId).map(f->new EventRuntimeSnapshot(true,Map.of("bossId",f.bossId,"entity",String.valueOf(f.bossEntityId)))).orElse(EventRuntimeSnapshot.unavailable());}
    private static Location parseLocation(EventModuleSettings root,String key){
        Object raw=root.get(key).orElse(null);
        if(!(raw instanceof Map<?,?> m))return null;
        Object worldRaw=m.get("world");
        String world=worldRaw==null?"":String.valueOf(worldRaw);
        var w=Bukkit.getWorld(world);
        if(w==null)return null;
        try{return new Location(w,num(m.get("x"),0),num(m.get("y"),0),num(m.get("z"),0),(float)num(m.get("yaw"),0),(float)num(m.get("pitch"),0));}
        catch(Exception e){return null;}
    }
    private static double num(Object o,double fallback){
        if(o==null)return fallback;
        return o instanceof Number n?n.doubleValue():Double.parseDouble(String.valueOf(o));
    }
}
