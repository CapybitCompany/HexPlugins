package hex.minions.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class MinionWikiHolder implements InventoryHolder {
    private final String typeId;

    public MinionWikiHolder(String typeId) {
        this.typeId = typeId == null ? "" : typeId;
    }

    public boolean index() {
        return typeId.isBlank();
    }

    public String typeId() {
        return typeId;
    }

    @Override
    public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("Holder only");
    }
}
