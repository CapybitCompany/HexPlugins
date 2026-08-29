package hex.events.provider;

import hex.events.api.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class VanillaItemRewardProvider implements RewardProvider {
    @Override public String type(){return "vanilla_item";}
    @Override public RewardDeliveryResult deliver(RewardContext context,RewardGrant grant){
        if(context.subjectType()!=ResultSubjectType.PLAYER)return RewardDeliveryResult.failed("vanilla_item requires PLAYER");
        Player p=Bukkit.getPlayer(context.subjectId());
        if(p==null||!p.isOnline())return RewardDeliveryResult.retry("PLAYER_OFFLINE");
        Material material=Material.matchMaterial(grant.settings().string("material",""));
        if(material==null||material.isAir())return RewardDeliveryResult.failed("Unknown material");
        int amount;try{amount=grant.amount().intValueExact();}catch(Exception e){return RewardDeliveryResult.failed("Item amount must be integer");}
        if(amount<=0)return RewardDeliveryResult.failed("Amount must be > 0");
        List<ItemStack> stacks=new ArrayList<>();int left=amount;int max=Math.max(1,material.getMaxStackSize());
        while(left>0){int n=Math.min(left,max);stacks.add(new ItemStack(material,n));left-=n;}
        if(!canFit(p,stacks))return RewardDeliveryResult.retry("INVENTORY_FULL");
        for(ItemStack stack:stacks)if(!p.getInventory().addItem(stack).isEmpty())return RewardDeliveryResult.reconcile("INVENTORY_CHANGED_DURING_DELIVERY");
        return RewardDeliveryResult.delivered();
    }
    private static boolean canFit(Player p,List<ItemStack> incoming){
        List<ItemStack> inv=new ArrayList<>();for(ItemStack s:p.getInventory().getStorageContents())inv.add(s==null?null:s.clone());
        for(ItemStack item:incoming){int left=item.getAmount();int max=Math.max(1,item.getMaxStackSize());
            for(ItemStack cur:inv){if(left<=0)break;if(cur==null||!cur.isSimilar(item))continue;int free=Math.max(0,max-cur.getAmount());int put=Math.min(free,left);cur.setAmount(cur.getAmount()+put);left-=put;}
            for(int i=0;i<inv.size()&&left>0;i++){if(inv.get(i)!=null)continue;int put=Math.min(max,left);ItemStack c=item.clone();c.setAmount(put);inv.set(i,c);left-=put;}
            if(left>0)return false;
        }return true;
    }
}
