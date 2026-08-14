package hex.auctionbazaar;

import hex.auctionbazaar.util.CustomItemTradePolicy;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomItemTradePolicyTest {

    private static final NamespacedKey HEX_CUSTOM_ITEM_ID =
            new NamespacedKey("hexcustomitems", "hexcustomitem_id");

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void vanillaItemWithVanillaEnchantmentsIsAllowed() {
        ItemStack item = new ItemStack(Material.DIAMOND_PICKAXE);
        item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 5);

        assertTrue(CustomItemTradePolicy.evaluate(item).allowed());
    }

    @Test
    void allowedHexCustomItemCanHaveCustomModelData() {
        ItemStack item = taggedHexItem(Material.RED_DYE, "hex:red_heart");
        ItemMeta meta = item.getItemMeta();
        meta.setCustomModelData(10002);
        item.setItemMeta(meta);

        assertTrue(CustomItemTradePolicy.evaluate(item).allowed());
    }

    @Test
    void blockedHexKeysCannotBeTraded() {
        assertFalse(CustomItemTradePolicy.evaluate(taggedHexItem(Material.TRIAL_KEY, "hex:afk_key")).allowed());
        assertFalse(CustomItemTradePolicy.evaluate(taggedHexItem(Material.TRIAL_KEY, "hex:epic_key")).allowed());
        assertFalse(CustomItemTradePolicy.evaluate(taggedHexItem(Material.TRIAL_KEY, "hex:premium_key")).allowed());
    }

    @Test
    void unknownHexCustomItemIsBlockedByDefault() {
        assertFalse(CustomItemTradePolicy.evaluate(
                taggedHexItem(Material.COOKIE, "hex:invisibility_cookie")).allowed());
    }

    @Test
    void foreignPersistentDataIsBlocked() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(
                new NamespacedKey("hexminions", "item_kind"), PersistentDataType.STRING, "minion");
        item.setItemMeta(meta);

        assertFalse(CustomItemTradePolicy.evaluate(item).allowed());
    }

    @Test
    void customModelDataWithoutAllowedHexIdIsBlocked() {
        ItemStack item = new ItemStack(Material.BLAZE_POWDER);
        ItemMeta meta = item.getItemMeta();
        meta.setCustomModelData(10011);
        item.setItemMeta(meta);

        assertFalse(CustomItemTradePolicy.evaluate(item).allowed());
    }

    private static ItemStack taggedHexItem(Material material, String id) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(HEX_CUSTOM_ITEM_ID, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }
}
