package hex.towns.api.event;

import hex.towns.model.Town;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class TownCoopLeftEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Town town;
    private final UUID playerId;
    private final String reason;

    public TownCoopLeftEvent(Town town, UUID playerId, String reason) {
        this.town = town;
        this.playerId = playerId;
        this.reason = reason;
    }

    public Town town() { return town; }
    public UUID playerId() { return playerId; }
    public String reason() { return reason; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}