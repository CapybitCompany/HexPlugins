package hexnpc.guide;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class GuideMenuHolder implements InventoryHolder {
    private final String menuId;
    private final String parentId;
    private final int backSlot;
    private final Map<Integer, String> targets;
    private Inventory inventory;

    public GuideMenuHolder(String menuId, String parentId, int backSlot, Map<Integer, String> targets) {
        this.menuId = menuId;
        this.parentId = parentId;
        this.backSlot = backSlot;
        this.targets = targets == null ? Map.of() : Map.copyOf(targets);
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("GuideMenuHolder accessed before bind");
        return inventory;
    }

    public String menuId() { return menuId; }
    public String parentId() { return parentId; }
    public int backSlot() { return backSlot; }
    public Map<Integer, String> targets() { return targets; }
}
