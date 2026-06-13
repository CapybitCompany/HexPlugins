package hex.auctionbazaar.bazaar.service;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;

/**
 * Returns true only for stacks that look like vanilla "plain" items of the
 * given material: no custom display name, no lore, no enchantments, no
 * persistent data, no damage. Used by the Bazaar sell flow so player
 * collectibles never get drained into the system at a flat price.
 */
public final class PlainItemMatcher {

    private PlainItemMatcher() {
    }

    public static boolean isPlain(ItemStack stack, Material target) {
        if (stack == null || stack.getType() != target) {
            return false;
        }
        if (stack.getAmount() <= 0) {
            return false;
        }
        if (!stack.hasItemMeta()) {
            return true;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return true;
        }
        if (meta.hasDisplayName()) return false;
        if (meta.hasLore()) return false;
        if (meta.hasEnchants()) return false;
        if (meta.hasCustomModelData()) return false;
        if (meta.hasAttributeModifiers()) return false;
        if (!meta.getPersistentDataContainer().isEmpty()) return false;
        if (meta instanceof Damageable d && d.hasDamage()) return false;
        return true;
    }
}
