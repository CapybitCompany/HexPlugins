package hex.restrictions.service;

import org.bukkit.inventory.ItemStack;

public record ItemSanitizeResult(ItemStack item, int removedItems, int removedEnchantments) {
    public boolean changed() {
        return removedItems > 0 || removedEnchantments > 0;
    }

    public boolean removedCompletely() {
        return removedItems > 0 || item == null;
    }

    public RestrictionAudit audit() {
        return new RestrictionAudit(removedItems, removedEnchantments, 0);
    }
}
