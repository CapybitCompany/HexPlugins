package hex.towns.api.event;

import hex.towns.model.Town;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class TownRenamedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Town town;
    private final UUID by;
    private final String name;

    public TownRenamedEvent(Town town, UUID by, String name) {
        this.town = town;
        this.by = by;
        this.name = name;
    }

    public Town town() { return town; }
    public UUID by() { return by; }
    public String name() { return name; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
