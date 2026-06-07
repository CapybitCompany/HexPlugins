package hex.collections.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.UUID;

public final class TownCollectionResetEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID townId;
    public TownCollectionResetEvent(UUID townId) { this.townId = townId; }
    public UUID townId() { return townId; }
    @Override public HandlerList getHandlers() { return HANDLERS; } public static HandlerList getHandlerList() { return HANDLERS; }
}

