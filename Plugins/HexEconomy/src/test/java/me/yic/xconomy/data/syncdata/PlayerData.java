package me.yic.xconomy.data.syncdata;
import java.math.BigDecimal; import java.util.UUID;
public final class PlayerData {
    private final UUID id; private final String name; private BigDecimal balance;
    public PlayerData(UUID id,String name,BigDecimal balance){this.id=id;this.name=name;this.balance=balance;}
    public UUID getUniqueId(){return id;} public String getName(){return name;} public BigDecimal getBalance(){return balance;} public void setBalance(BigDecimal value){balance=value;}
}
