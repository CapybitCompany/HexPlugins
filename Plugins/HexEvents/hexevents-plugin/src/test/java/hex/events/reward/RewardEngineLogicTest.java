package hex.events.reward;

import hex.events.api.*;
import hex.events.model.EventDefinition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

public final class RewardEngineLogicTest {
    public static void main(String[] args){
        RewardEngine engine=new RewardEngine();
        UUID a=UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID b=UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID c=UUID.fromString("00000000-0000-0000-0000-000000000003");
        EventResult raw=EventResult.success(List.of(
                s(a,50,10),s(b,30,8),s(c,20,5)
        ));
        ProcessedResult processed=engine.enrich(raw);
        check(metric(processed,a,"rank")==1,"rank A");
        check(close(metric(processed,b,"damage_share_percent"),30.0),"share B");

        EventDefinition def=definition();
        List<RewardPlanEntry> plan=engine.plan(def,processed);
        // top 34% of 3 => ceil(1.02)=2 winners, one fixed money each
        long top=plan.stream().filter(x->x.ruleId().equals("top")).count();
        check(top==2,"TOP_PERCENT expected 2");
        // remaining excludes top => one player
        long remaining=plan.stream().filter(x->x.ruleId().equals("remaining")).count();
        check(remaining==1,"remaining expected 1");
        // pool reward is proportional across all three and sums to 1000.00
        BigDecimal pool=plan.stream().filter(x->x.ruleId().equals("pool")).map(x->x.grant().amount()).reduce(BigDecimal.ZERO,BigDecimal::add);
        check(pool.compareTo(new BigDecimal("1000.00"))==0 || pool.compareTo(new BigDecimal("1000"))==0,"pool sum="+pool);
        System.out.println("RewardEngineLogicTest OK");
    }
    static ResultSubject s(UUID id,double damage,double hits){return new ResultSubject(ResultSubjectType.PLAYER,id,Map.of("damage",damage,"hits",hits),Set.of());}
    static double metric(ProcessedResult r,UUID id,String k){return r.subjects().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow().metrics().getOrDefault(k,0.0);}
    static boolean close(double a,double b){return Math.abs(a-b)<0.0001;}
    static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
    static EventDefinition definition(){
        var fixed=new EventDefinition.RewardAmount(EventDefinition.RewardAmountType.FIXED,"damage",new BigDecimal("10"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,null,null,RoundingMode.DOWN);
        var pool=new EventDefinition.RewardAmount(EventDefinition.RewardAmountType.POOL_SHARE,"damage",BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("1000"),null,null,RoundingMode.DOWN);
        var rules=List.of(
                new EventDefinition.RewardRule("top",ResultSubjectType.PLAYER,new EventDefinition.RewardSelector(EventDefinition.RewardSelectorType.TOP_PERCENT,"damage",0,34,1,0,List.of()),List.of(new EventDefinition.RewardGrantSpec("money",fixed,EventModuleSettings.empty()))),
                new EventDefinition.RewardRule("remaining",ResultSubjectType.PLAYER,new EventDefinition.RewardSelector(EventDefinition.RewardSelectorType.REMAINING_ELIGIBLE,"damage",0,0,0,0,List.of("top")),List.of(new EventDefinition.RewardGrantSpec("money",fixed,EventModuleSettings.empty()))),
                new EventDefinition.RewardRule("pool",ResultSubjectType.PLAYER,new EventDefinition.RewardSelector(EventDefinition.RewardSelectorType.PARTICIPATION,"damage",0,0,0,0,List.of()),List.of(new EventDefinition.RewardGrantSpec("money",pool,EventModuleSettings.empty())))
        );
        return new EventDefinition("boss",true,"Boss","","CLOCK","hex:boss",EventModuleSettings.empty(),new EventDefinition.Schedule(ZoneId.of("UTC"),List.of(new EventDefinition.WeeklySlot(DayOfWeek.MONDAY,LocalTime.NOON))),Duration.ofMinutes(20),Duration.ZERO,new EventDefinition.RegistrationPolicy(EventDefinition.RegistrationMode.REQUIRED,Duration.ofHours(1),EventDefinition.CancelUntil.START),new EventDefinition.LobbyPolicy(false,Duration.ZERO),new EventDefinition.CapacityPolicy(0,100,EventDefinition.TooFewPolicy.START_ANYWAY),new EventDefinition.JoinPolicy(true,true,Duration.ofMinutes(5),EventDefinition.LateJoinScope.REGISTERED_ONLY,true),List.of(),List.of(),rules,List.of(),List.of(),Map.of());
    }
}
