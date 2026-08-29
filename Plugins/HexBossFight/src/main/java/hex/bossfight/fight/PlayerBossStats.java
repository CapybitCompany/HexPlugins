package hex.bossfight.fight;

import java.time.Instant;

public final class PlayerBossStats {
    public double damage;
    public int hits;
    public int deaths;
    public Instant joinedAt;
    public Instant leftAt;
    public long activeMillis;
    public void join(Instant now){ if(joinedAt==null) joinedAt=now; leftAt=null; }
    public void leave(Instant now){ if(joinedAt!=null){ activeMillis+=Math.max(0,now.toEpochMilli()-joinedAt.toEpochMilli()); joinedAt=null; } leftAt=now; }
    public long activeSeconds(Instant now){ long ms=activeMillis+(joinedAt==null?0:Math.max(0,now.toEpochMilli()-joinedAt.toEpochMilli()));return ms/1000; }
}
