package hex.towns.api.event;

import hex.towns.model.Town;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class TownCoopJoinedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Town town;
    private final UUID playerId;

    public TownCoopJoinedEvent(Town town, UUID playerId) {
        this.town = town;
        this.playerId = playerId;
    }

    public Town town() { return town; }
    public UUID playerId() { return playerId; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}