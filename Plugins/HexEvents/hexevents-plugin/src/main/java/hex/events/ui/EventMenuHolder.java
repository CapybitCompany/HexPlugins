package hex.events.ui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EventMenuHolder implements InventoryHolder {
    public enum Type { CALENDAR, DAY, CONFIRM_REGISTER, CONFIRM_CANCEL }
    private final Type type;
    private final LocalDate date;
    private final UUID instanceId;
    private final Map<Integer, Object> actions = new HashMap<>();
    private Inventory inventory;

    public EventMenuHolder(Type type, LocalDate date, UUID instanceId) { this.type=type; this.date=date; this.instanceId=instanceId; }
    public Type type(){return type;} public LocalDate date(){return date;} public UUID instanceId(){return instanceId;}
    public Map<Integer,Object> actions(){return actions;} public void bind(Inventory inventory){this.inventory=inventory;}
    @Override public Inventory getInventory(){return inventory;}
}
