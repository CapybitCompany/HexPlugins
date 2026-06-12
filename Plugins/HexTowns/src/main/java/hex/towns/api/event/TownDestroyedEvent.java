package hex.towns.api.event;

import hex.towns.model.Town;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import hex.towns.model.ChunkPos;

import java.util.List;
import java.util.UUID;

public final class TownDestroyedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Town town;
    private final UUID by;
    private final List<UUID> affectedPlayers;
    private final List<ChunkPos> chunks;

    public TownDestroyedEvent(Town town, UUID by, List<UUID> affectedPlayers) {
        this(town, by, affectedPlayers, List.of());
    }

    public TownDestroyedEvent(Town town, UUID by, List<UUID> affectedPlayers, List<ChunkPos> chunks) {
        this.town = town;
        this.by = by;
        this.affectedPlayers = List.copyOf(affectedPlayers);
        this.chunks = List.copyOf(chunks);
    }

    public Town town() { return town; }
    public UUID by() { return by; }
    public List<UUID> affectedPlayers() { return affectedPlayers; }
    public List<ChunkPos> chunks() { return chunks; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}