package me.yic.xconomy.api;
import java.math.BigDecimal; import java.util.*; import me.yic.xconomy.data.syncdata.PlayerData;
public class XConomyAPI {
    private static final Map<UUID,PlayerData> DATA=new HashMap<>();
    public boolean createPlayerData(UUID id,String name){ DATA.putIfAbsent(id,new PlayerData(id,name,BigDecimal.ZERO)); return true; }
    public PlayerData getPlayerData(UUID id){return DATA.get(id);}
    public PlayerData getPlayerData(String name){return DATA.values().stream().filter(p->p.getName().equalsIgnoreCase(name)).findFirst().orElse(null);}
    public int changePlayerBalance(UUID id,String name,BigDecimal amount,Boolean add,String plugin){
        PlayerData p=DATA.get(id); if(p==null)return 1; BigDecimal balance=p.getBalance();
        if(Boolean.FALSE.equals(add)&&balance.compareTo(amount)<0)return 2;
        BigDecimal next=add==null?amount:(add?balance.add(amount):balance.subtract(amount));
        if(next.compareTo(new BigDecimal("1000000"))>0)return 3; p.setBalance(next); return 0;
    }
}
