package hexnpc.workflow.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class WorkflowMenuHolder implements InventoryHolder {
    private final String menuId;
    private Inventory inventory;

    public WorkflowMenuHolder(String menuId) {
        this.menuId = menuId;
    }

    public String menuId() { return menuId; }
    void bind(Inventory inventory) { this.inventory = inventory; }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("WorkflowMenuHolder accessed before bind");
        return inventory;
    }
}
