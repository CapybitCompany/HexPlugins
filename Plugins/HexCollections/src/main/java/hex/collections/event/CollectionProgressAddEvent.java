package hex.collections.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.UUID;

public final class CollectionProgressAddEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID townId;
    private final UUID playerUuid;
    private final String collectionId;
    private final long amount;

    public CollectionProgressAddEvent(UUID townId, UUID playerUuid, String collectionId, long amount) {
        this.townId = townId; this.playerUuid = playerUuid; this.collectionId = collectionId; this.amount = amount;
    }
    public UUID townId() { return townId; }
    public UUID playerUuid() { return playerUuid; }
    public String collectionId() { return collectionId; }
    public long amount() { return amount; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

