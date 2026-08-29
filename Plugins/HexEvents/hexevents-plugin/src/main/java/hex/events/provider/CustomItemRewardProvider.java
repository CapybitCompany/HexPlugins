package hex.events.provider;

import hex.events.api.*;
import hexcustomitems.api.HexCustomItemsApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class CustomItemRewardProvider implements RewardProvider {
    private final HexCustomItemsApi api;
    public CustomItemRewardProvider(HexCustomItemsApi api){this.api=api;}
    @Override public String type(){return "custom_item";}
    @Override public RewardDeliveryResult deliver(RewardContext context,RewardGrant grant){
        if(context.subjectType()!=ResultSubjectType.PLAYER)return RewardDeliveryResult.failed("custom_item requires PLAYER");
        Player p=Bukkit.getPlayer(context.subjectId());
        if(p==null||!p.isOnline())return RewardDeliveryResult.retry("PLAYER_OFFLINE");
        int amount;
        try{amount=grant.amount().intValueExact();}catch(Exception e){return RewardDeliveryResult.failed("Item amount must be integer");}
        String itemId=grant.settings().string("item-id","");
        if(itemId.isBlank())return RewardDeliveryResult.failed("Missing item-id");
        var r=api.give(p,itemId,amount);
        if(r.success()) return RewardDeliveryResult.delivered();
        String reason=r.reason()==null?"UNKNOWN":r.reason();
        if("INVENTORY_FULL".equalsIgnoreCase(reason)) return RewardDeliveryResult.retry(reason);
        if("INVENTORY_CHANGED".equalsIgnoreCase(reason)) return RewardDeliveryResult.reconcile(reason);
        return RewardDeliveryResult.failed(reason);
    }
}
