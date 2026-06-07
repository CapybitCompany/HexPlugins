package hex.collections.api;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class CollectionProgressContext {
    private UUID playerUuid;
    private UUID townId;
    private String collectionId;
    private long amount;
    private CollectionSource source = CollectionSource.UNKNOWN;
    private Location location;
    private ItemStack itemStack;
    private String reason = "";
    private boolean allowMultiplier;
    private boolean triggerRewards = true;

    public UUID playerUuid() { return playerUuid; }
    public UUID townId() { return townId; }
    public String collectionId() { return collectionId; }
    public long amount() { return amount; }
    public CollectionSource source() { return source; }
    public Location location() { return location; }
    public ItemStack itemStack() { return itemStack; }
    public String reason() { return reason; }
    public boolean allowMultiplier() { return allowMultiplier; }
    public boolean triggerRewards() { return triggerRewards; }

    public CollectionProgressContext playerUuid(UUID value) { this.playerUuid = value; return this; }
    public CollectionProgressContext townId(UUID value) { this.townId = value; return this; }
    public CollectionProgressContext collectionId(String value) { this.collectionId = value; return this; }
    public CollectionProgressContext amount(long value) { this.amount = value; return this; }
    public CollectionProgressContext source(CollectionSource value) { this.source = value == null ? CollectionSource.UNKNOWN : value; return this; }
    public CollectionProgressContext location(Location value) { this.location = value; return this; }
    public CollectionProgressContext itemStack(ItemStack value) { this.itemStack = value; return this; }
    public CollectionProgressContext reason(String value) { this.reason = value == null ? "" : value; return this; }
    public CollectionProgressContext allowMultiplier(boolean value) { this.allowMultiplier = value; return this; }
    public CollectionProgressContext triggerRewards(boolean value) { this.triggerRewards = value; return this; }
}

