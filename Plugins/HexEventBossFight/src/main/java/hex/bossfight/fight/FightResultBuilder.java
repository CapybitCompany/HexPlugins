package hex.bossfight.fight;

import hex.events.api.*;

import java.time.Instant;
import java.util.*;

public final class FightResultBuilder {
    public EventResult success(ActiveBossFight fight,Instant now){
        List<ResultSubject> subjects=new ArrayList<>();
        for(UUID id:fight.participants){
            PlayerBossStats s=fight.stats(id);
            Map<String,Double> metrics=new LinkedHashMap<>();
            metrics.put("damage",s.damage);metrics.put("hits",(double)s.hits);metrics.put("deaths",(double)s.deaths);metrics.put("active_seconds",(double)s.activeSeconds(now));
            subjects.add(new ResultSubject(ResultSubjectType.PLAYER,id,metrics,Set.of("BOSS_KILLED")));
        }
        return new EventResult(EventOutcome.SUCCESS,subjects,Map.of("boss_id",fight.bossId));
    }
}

