package hexnpc.shop.inventory;

import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.ShopItem;
import hexnpc.util.LegacyFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Centralna fabryka ItemStack dla pozycji sklepu. */
public final class ShopItemStackFactory {

    private ShopItemStackFactory() {
    }

    public static ItemStack exactTemplate(ShopItem item) {
        ItemStack stack = new ItemStack(item.material(), item.amount());
        applyConfiguredMeta(stack, item, List.of());
        return stack;
    }

    public static ItemStack tradeStack(ShopItem item) {
        if (item.sellMatch() == SellMatch.PLAIN_MATERIAL) {
            return new ItemStack(item.material(), item.amount());
        }
        return exactTemplate(item);
    }

    public static ItemStack tradeUnit(ShopItem item) {
        if (item.sellMatch() == SellMatch.PLAIN_MATERIAL) {
            return new ItemStack(item.material(), 1);
        }
        ItemStack stack = exactTemplate(item);
        stack.setAmount(1);
        return stack;
    }

    public static ItemStack displayStack(ShopItem item, List<Component> extraLore) {
        return displayStack(item, extraLore, null);
    }

    /**
     * Wariant z zewnętrzną bazą ikony (np. prawdziwy item HexCustomItems).
     * Zachowujemy PDC/model z bazy, a nazwa/lore z shops.yml jest warstwą GUI.
     */
    public static ItemStack displayStack(ShopItem item, List<Component> extraLore, ItemStack base) {
        ItemStack stack = base == null
                ? new ItemStack(item.material(), item.amount())
                : base.clone();
        if (stack.getAmount() < 1) stack.setAmount(1);
        applyConfiguredMeta(stack, item, extraLore == null ? List.of() : extraLore);
        return stack;
    }

    private static void applyConfiguredMeta(ItemStack stack, ShopItem item, List<Component> extraLore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;

        String displayName = item.displayName();
        if (displayName != null && !displayName.isEmpty()) {
            meta.displayName(LegacyFormat.component(displayName));
        }
        if (item.customModelData() > 0) {
            try {
                meta.setCustomModelData(item.customModelData());
            } catch (Throwable ignored) {
                // Kompatybilność z przyszłym API komponentów item-model.
            }
        }

        List<Component> lore = new ArrayList<>();
        for (String line : item.lore()) {
            lore.add(LegacyFormat.component(line));
        }
        lore.addAll(extraLore);
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        stack.setItemMeta(meta);
    }
}
