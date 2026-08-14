package hex.auctionbazaar.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.Set;

/**
 * AuctionHouse trade gate for plugin-created custom items.
 *
 * Vanilla metadata such as enchantments, damage or potion data is allowed. Plugin markers
 * (PDC keys) and custom model data are treated as custom-item ownership signals.
 */
public final class CustomItemTradePolicy {

    private static final NamespacedKey HEX_CUSTOM_ITEM_ID =
            new NamespacedKey("hexcustomitems", "hexcustomitem_id");
    private static final String HEX_CUSTOM_ITEMS_NAMESPACE = "hexcustomitems";

    private static final Set<String> ALLOWED_HEX_CUSTOM_ITEMS = Set.of(
            "hex:boss_ticket",
            "hex:red_heart",
            "hex:golden_heart",
            "hex:efficiency_6_book",
            "hex:ancient_scale",
            "hex:darkness_powder",
            "hex:spider_grenade",
            "hex:phoenix_heart",
            "hex:butcher_hook",
            "hex:mining_luck",
            "hex:hunter_skull",
            "hex:kinetic_charge"
    );

    private static final Set<String> BLOCKED_HEX_CUSTOM_ITEMS = Set.of(
            "hex:afk_key",
            "hex:epic_key",
            "hex:premium_key"
    );

    private CustomItemTradePolicy() {
    }

    public record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, "allowed");
        }

        public static Decision block(String reason) {
            return new Decision(false, reason);
        }
    }

    public static Decision evaluate(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) {
            return Decision.block("empty item");
        }
        if (!stack.hasItemMeta()) {
            return Decision.allow();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Decision.allow();
        }

        String hexCustomItemId = hexCustomItemId(meta);
        if (hexCustomItemId != null) {
            if (ALLOWED_HEX_CUSTOM_ITEMS.contains(hexCustomItemId)) {
                return hasForeignPersistentData(meta)
                        ? Decision.block("foreign custom item data")
                        : Decision.allow();
            }
            if (BLOCKED_HEX_CUSTOM_ITEMS.contains(hexCustomItemId)) {
                return Decision.block("blocked HexCustomItems item: " + hexCustomItemId);
            }
            return Decision.block("unknown HexCustomItems item: " + hexCustomItemId);
        }

        if (hasForeignPersistentData(meta)) {
            return Decision.block("foreign custom item data");
        }
        if (meta.hasCustomModelData()) {
            return Decision.block("custom model data without allowed HexCustomItems id");
        }
        return Decision.allow();
    }

    private static String hexCustomItemId(ItemMeta meta) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String id = pdc.get(HEX_CUSTOM_ITEM_ID, PersistentDataType.STRING);
        return normalize(id);
    }

    private static boolean hasForeignPersistentData(ItemMeta meta) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            if (!HEX_CUSTOM_ITEMS_NAMESPACE.equals(key.getNamespace())) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
