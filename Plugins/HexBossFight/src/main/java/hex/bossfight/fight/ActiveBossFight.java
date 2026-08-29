package hex.bossfight.fight;

import hex.events.api.EventExecutionContext;

import java.time.Instant;
import java.util.*;

public final class ActiveBossFight {
    public final UUID instanceId;
    public final String bossId;
    public final int spawnLocation;
    public final EventExecutionContext context;
    public UUID bossEntityId;
    public Instant startedAt;
    public final Set<UUID> participants=new LinkedHashSet<>();
    public final Map<UUID,PlayerBossStats> stats=new LinkedHashMap<>();
    public ActiveBossFight(EventExecutionContext context,String bossId,int spawnLocation){this.instanceId=context.instanceId();this.context=context;this.bossId=bossId;this.spawnLocation=spawnLocation;}
    public PlayerBossStats stats(UUID player){return stats.computeIfAbsent(player,k->new PlayerBossStats());}
}
