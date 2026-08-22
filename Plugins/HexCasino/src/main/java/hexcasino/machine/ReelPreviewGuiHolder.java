package hexcasino.machine;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class ReelPreviewGuiHolder implements InventoryHolder {
    private final UUID playerId;
    private final int reelSetIndex;
    private final int reelIndex;
    private final int page;
    private Inventory inventory;

    public ReelPreviewGuiHolder(UUID playerId, int reelSetIndex, int reelIndex, int page) {
        this.playerId = playerId;
        this.reelSetIndex = reelSetIndex;
        this.reelIndex = reelIndex;
        this.page = page;
    }

    public UUID playerId() { return playerId; }
    public int reelSetIndex() { return reelSetIndex; }
    public int reelIndex() { return reelIndex; }
    public int page() { return page; }
    public void inventory(Inventory inventory) { this.inventory = inventory; }
    @Override public Inventory getInventory() { return inventory; }
}
