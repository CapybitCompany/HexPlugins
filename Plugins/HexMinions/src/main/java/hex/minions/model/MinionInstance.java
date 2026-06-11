package hex.minions.model;

import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class MinionInstance {
    private final UUID id;
    private final long townInternalId;
    private final UUID townUuid;
    private final UUID ownerUuid;
    private final String typeId;
    private final AtomicInteger tier;
    private volatile MinionLocation location;
    private volatile MinionState state;
    private final long placedAt;
    private volatile long lastActionAt;
    private volatile long nextActionAt;
    private final AtomicInteger storageUsed;
    private volatile int storageLimit;
    private final Map<String, Long> storage = new ConcurrentHashMap<>();
    private final Map<String, ItemStack> addonItems = new ConcurrentHashMap<>();
    private volatile String appearanceId;

    public MinionInstance(UUID id, long townInternalId, UUID townUuid, UUID ownerUuid, String typeId, int tier,
                          MinionLocation location, MinionState state, long placedAt, long lastActionAt,
                          long nextActionAt, int storageUsed, int storageLimit, String appearanceId) {
        this.id = id;
        this.townInternalId = townInternalId;
        this.townUuid = townUuid;
        this.ownerUuid = ownerUuid;
        this.typeId = typeId;
        this.tier = new AtomicInteger(tier);
        this.location = location;
        this.state = state;
        this.placedAt = placedAt;
        this.lastActionAt = lastActionAt;
        this.nextActionAt = nextActionAt;
        this.storageUsed = new AtomicInteger(storageUsed);
        this.storageLimit = storageLimit;
        this.appearanceId = appearanceId;
    }

    public UUID id() { return id; }
    public long townInternalId() { return townInternalId; }
    public UUID townUuid() { return townUuid; }
    public UUID ownerUuid() { return ownerUuid; }
    public String typeId() { return typeId; }
    public int tier() { return tier.get(); }
    public MinionLocation location() { return location; }
    public MinionState state() { return state; }
    public long placedAt() { return placedAt; }
    public long lastActionAt() { return lastActionAt; }
    public long nextActionAt() { return nextActionAt; }
    public int storageUsed() { return storageUsed.get(); }
    public int storageLimit() { return storageLimit; }
    public String appearanceId() { return appearanceId; }
    public Map<String, Long> storage() { return storage; }
    public Map<String, ItemStack> addonItems() { return addonItems; }

    public void setLocation(MinionLocation location) { this.location = location; }
    public void setState(MinionState state) { this.state = state; }
    public void setLastActionAt(long value) { this.lastActionAt = value; }
    public void setNextActionAt(long value) { this.nextActionAt = value; }
    public void setStorageLimit(int value) { this.storageLimit = value; }
    public void setAppearanceId(String value) { this.appearanceId = value; }
    public int incrementTier() { return tier.incrementAndGet(); }

    public boolean hasStorageSpace() {
        return storageUsed.get() < storageLimit;
    }

    public long addStorage(String resourceId, long amount) {
        if (amount <= 0 || !hasStorageSpace()) {
            return 0;
        }
        int remainingSlots = Math.max(0, storageLimit - storageUsed.get());
        long accepted = Math.min(amount, remainingSlots);
        if (accepted <= 0) {
            return 0;
        }
        storage.merge(resourceId, accepted, Long::sum);
        storageUsed.addAndGet((int) accepted);
        return accepted;
    }

    public Map<String, Long> drainStorage() {
        Map<String, Long> copy = Map.copyOf(storage);
        storage.clear();
        storageUsed.set(0);
        return copy;
    }

    public void replaceAddonItems(Map<String, ItemStack> values) {
        addonItems.clear();
        for (Map.Entry<String, ItemStack> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().getType().isAir()) continue;
            addonItems.put(entry.getKey(), entry.getValue().clone());
        }
    }

    public void setAddonItem(String slot, ItemStack item) {
        if (slot == null || slot.isBlank()) return;
        if (item == null || item.getType().isAir()) {
            addonItems.remove(slot);
        } else {
            addonItems.put(slot, item.clone());
        }
    }

    public boolean hasAddonItems() {
        return addonItems.values().stream().anyMatch(item -> item != null && !item.getType().isAir());
    }

    public void replaceStorage(Map<String, Long> values) {
        storage.clear();
        long used = 0L;
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            storage.put(entry.getKey(), entry.getValue());
            used += entry.getValue();
        }
        storageUsed.set((int) Math.min(Integer.MAX_VALUE, used));
    }
}

