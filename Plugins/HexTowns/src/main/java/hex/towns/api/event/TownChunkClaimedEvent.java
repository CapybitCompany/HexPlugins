package hex.towns.api.event;

import hex.towns.model.ChunkPos;
import hex.towns.model.Town;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class TownChunkClaimedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Town town;
    private final ChunkPos chunk;
    private final UUID by;

    public TownChunkClaimedEvent(Town town, ChunkPos chunk, UUID by) {
        this.town = town;
        this.chunk = chunk;
        this.by = by;
    }

    public Town town() { return town; }
    public ChunkPos chunk() { return chunk; }
    public UUID by() { return by; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}