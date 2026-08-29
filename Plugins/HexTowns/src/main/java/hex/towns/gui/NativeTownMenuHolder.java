package hex.towns.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class NativeTownMenuHolder implements InventoryHolder {
    private final Page page;
    private final UUID viewerId;

    public NativeTownMenuHolder(Page page, UUID viewerId) {
        this.page = page;
        this.viewerId = viewerId;
    }

    public Page page() { return page; }
    public UUID viewerId() { return viewerId; }

    @Override
    public Inventory getInventory() { return null; }

    public enum Page {
        MAIN,
        MANAGE,
        CLAIMS,
        COOP,
        COLLECTIONS_RESOURCES,
        COLLECTIONS_FARMING,
        COLLECTIONS_ANIMALS,
        COLLECTIONS_MOBS,
        MINIONS,
        BANK,
        GUIDE,
        GUIDE_GROWTH,
        GUIDE_PLAYERS,
        DUMMY_PERMISSIONS,
        DANGER
    }
}
