package hex.restrictions.service;

import hex.restrictions.config.RestrictionSettings;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;

public final class RestrictionService {
    private volatile RestrictionSettings settings;

    public RestrictionService(RestrictionSettings settings) {
        this.settings = settings;
    }

    public RestrictionSettings settings() {
        return settings;
    }

    public void updateSettings(RestrictionSettings settings) {
        this.settings = settings;
    }

    public boolean isEnabled() {
        return settings.enabled();
    }

    public boolean isForbiddenMaterial(Material material) {
        return isEnabled() && material != null && settings.forbiddenItems().contains(material);
    }

    public boolean isForbiddenEnchantment(Enchantment enchantment) {
        if (!isEnabled() || enchantment == null) return false;
        return settings.isForbiddenEnchantmentKey(enchantment.getKey().toString());
    }

    public boolean hasForbiddenEnchantment(ItemStack item) {
        if (!isEnabled() || item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        for (Enchantment enchantment : meta.getEnchants().keySet()) {
            if (isForbiddenEnchantment(enchantment)) return true;
        }
        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            for (Enchantment enchantment : storageMeta.getStoredEnchants().keySet()) {
                if (isForbiddenEnchantment(enchantment)) return true;
            }
        }
        return false;
    }

    public boolean hasForbiddenContent(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && (isForbiddenMaterial(item.getType()) || hasForbiddenEnchantment(item));
    }

    public ItemSanitizeResult sanitizeItem(ItemStack original) {
        if (!isEnabled() || original == null || original.getType().isAir()) {
            return new ItemSanitizeResult(original, 0, 0);
        }

        if (isForbiddenMaterial(original.getType())) {
            return new ItemSanitizeResult(null, original.getAmount(), 0);
        }

        if (!original.hasItemMeta()) {
            return new ItemSanitizeResult(original, 0, 0);
        }

        ItemStack sanitized = original.clone();
        ItemMeta meta = sanitized.getItemMeta();
        int removedEnchantments = 0;

        for (Enchantment enchantment : new ArrayList<>(meta.getEnchants().keySet())) {
            if (isForbiddenEnchantment(enchantment) && meta.removeEnchant(enchantment)) {
                removedEnchantments++;
            }
        }

        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            for (Enchantment enchantment : new ArrayList<>(storageMeta.getStoredEnchants().keySet())) {
                if (isForbiddenEnchantment(enchantment) && storageMeta.removeStoredEnchant(enchantment)) {
                    removedEnchantments++;
                }
            }
        }

        if (removedEnchantments > 0) {
            sanitized.setItemMeta(meta);
            return new ItemSanitizeResult(sanitized, 0, removedEnchantments);
        }

        return new ItemSanitizeResult(original, 0, 0);
    }

    public RestrictionAudit sanitizeInventory(Inventory inventory) {
        if (!isEnabled() || inventory == null) return RestrictionAudit.NONE;

        RestrictionAudit audit = RestrictionAudit.NONE;
        ItemStack[] contents = inventory.getContents();
        int slots = Math.min(contents.length, inventory.getSize());
        for (int slot = 0; slot < slots; slot++) {
            ItemStack current = contents[slot];
            ItemSanitizeResult result = sanitizeItem(current);
            if (!result.changed()) continue;
            inventory.setItem(slot, result.item());
            audit = audit.plus(result.audit());
        }
        return audit;
    }

    public RestrictionAudit sanitizePlayer(Player player) {
        if (!isEnabled() || player == null) return RestrictionAudit.NONE;

        RestrictionAudit audit = sanitizeInventory(player.getInventory())
                .plus(sanitizeInventory(player.getEnderChest()));

        ItemStack cursor = player.getItemOnCursor();
        ItemSanitizeResult cursorResult = sanitizeItem(cursor);
        if (cursorResult.changed()) {
            player.setItemOnCursor(cursorResult.item() == null ? new ItemStack(Material.AIR) : cursorResult.item());
            audit = audit.plus(cursorResult.audit());
        }
        return audit;
    }
}
