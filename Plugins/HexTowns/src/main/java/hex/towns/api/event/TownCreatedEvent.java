package hex.towns.api.event;

import hex.towns.model.Town;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class TownCreatedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Town town;
    private final UUID ownerId;

    public TownCreatedEvent(Town town, UUID ownerId) {
        this.town = town;
        this.ownerId = ownerId;
    }

    public Town town() { return town; }
    public UUID ownerId() { return ownerId; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}