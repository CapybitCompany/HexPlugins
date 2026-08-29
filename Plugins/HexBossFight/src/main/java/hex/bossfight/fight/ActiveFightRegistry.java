package hex.bossfight.fight;

import java.util.*;

public final class ActiveFightRegistry {
    private final Map<UUID,ActiveBossFight> byInstance=new LinkedHashMap<>();
    private final Map<UUID,ActiveBossFight> byEntity=new HashMap<>();
    public void put(ActiveBossFight fight){byInstance.put(fight.instanceId,fight);if(fight.bossEntityId!=null)byEntity.put(fight.bossEntityId,fight);}
    public void bindEntity(ActiveBossFight fight,UUID entity){ if(fight.bossEntityId!=null)byEntity.remove(fight.bossEntityId);fight.bossEntityId=entity;byEntity.put(entity,fight); }
    public void unbindEntity(ActiveBossFight fight){ if(fight!=null&&fight.bossEntityId!=null){byEntity.remove(fight.bossEntityId);fight.bossEntityId=null;} }
    public Optional<ActiveBossFight> byInstance(UUID id){return Optional.ofNullable(byInstance.get(id));}
    public Optional<ActiveBossFight> byEntity(UUID id){return Optional.ofNullable(byEntity.get(id));}
    public Collection<ActiveBossFight> all(){return List.copyOf(byInstance.values());}
    public ActiveBossFight remove(UUID instance){ActiveBossFight f=byInstance.remove(instance);if(f!=null&&f.bossEntityId!=null)byEntity.remove(f.bossEntityId);return f;}
    public void clear(){byInstance.clear();byEntity.clear();}
}
