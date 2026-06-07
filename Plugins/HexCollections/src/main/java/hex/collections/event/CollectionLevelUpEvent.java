package hex.collections.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.UUID;

public final class CollectionLevelUpEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID townId; private final UUID playerUuid; private final String collectionId; private final int level;
    public CollectionLevelUpEvent(UUID townId, UUID playerUuid, String collectionId, int level) { this.townId = townId; this.playerUuid = playerUuid; this.collectionId = collectionId; this.level = level; }
    public UUID townId() { return townId; } public UUID playerUuid() { return playerUuid; } public String collectionId() { return collectionId; } public int level() { return level; }
    @Override public HandlerList getHandlers() { return HANDLERS; } public static HandlerList getHandlerList() { return HANDLERS; }
}

