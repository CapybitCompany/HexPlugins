package hexcustomitems.ui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Eigenes Inventar-Holder-Objekt für das paginierte Give-Menü.
 * Über die Holder-Identität erkennt der Listener sicher, dass ein Klick zu diesem Menü gehört.
 */
public final class ItemsMenu implements InventoryHolder {

    public static final int SIZE = 54;
    public static final int CONTENT_SLOTS = 45;
    public static final int SLOT_PREV = 45;
    public static final int SLOT_INFO = 49;
    public static final int SLOT_NEXT = 53;

    private final int page;
    private final int pageCount;
    private final UUID targetId;
    private Inventory inventory;

    public ItemsMenu(int page, int pageCount, UUID targetId) {
        this.page = page;
        this.pageCount = pageCount;
        this.targetId = targetId;
    }

    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    public int page() {
        return page;
    }

    public int pageCount() {
        return pageCount;
    }

    /** UUID des Zielspielers, für den Items gegeben werden; {@code null} = an den öffnenden Spieler selbst. */
    public UUID targetId() {
        return targetId;
    }

    public void open(Player viewer) {
        viewer.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
