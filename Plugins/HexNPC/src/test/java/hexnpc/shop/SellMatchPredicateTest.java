package hexnpc.shop;

import hexnpc.shop.inventory.SellMatchPredicate;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.ShopItem;
import net.kyori.adventure.text.Component;
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
import org.mockbukkit.mockbukkit.ServerMock;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SellMatchPredicateTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private ShopItem plainItem() {
        return new ShopItem(
                "diamond", Material.DIAMOND, 1, 0,
                "", List.of(), BigDecimal.ZERO, new BigDecimal("10"),
                false, true, SellMatch.PLAIN_MATERIAL
        );
    }

    @Test
    void plainVanillaItemMatches() {
        ItemStack diamond = new ItemStack(Material.DIAMOND, 4);
        Predicate<ItemStack> p = SellMatchPredicate.of(plainItem(),
                new ItemStack(Material.DIAMOND, 1), true);
        assertTrue(p.test(diamond));
    }

    @Test
    void wrongMaterialDoesNotMatch() {
        Predicate<ItemStack> p = SellMatchPredicate.of(plainItem(),
                new ItemStack(Material.DIAMOND, 1), true);
        assertFalse(p.test(new ItemStack(Material.EMERALD)));
    }

    @Test
    void customNameBlocksPlainMatch() {
        ItemStack named = new ItemStack(Material.DIAMOND);
        ItemMeta meta = named.getItemMeta();
        meta.displayName(Component.text("Custom Diamond"));
        named.setItemMeta(meta);

        Predicate<ItemStack> p = SellMatchPredicate.of(plainItem(),
                new ItemStack(Material.DIAMOND, 1), true);
        assertFalse(p.test(named), "itemy z nazwą nie mogą być akceptowane przez PLAIN_MATERIAL");
    }

    @Test
    void loreBlocksPlainMatch() {
        ItemStack lored = new ItemStack(Material.DIAMOND);
        ItemMeta meta = lored.getItemMeta();
        meta.lore(List.of(Component.text("Custom lore")));
        lored.setItemMeta(meta);

        Predicate<ItemStack> p = SellMatchPredicate.of(plainItem(),
                new ItemStack(Material.DIAMOND, 1), true);
        assertFalse(p.test(lored), "itemy z lore nie mogą być akceptowane przez PLAIN_MATERIAL");
    }

    @Test
    void enchantedItemBlocksPlainMatch() {
        ItemStack ench = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = ench.getItemMeta();
        meta.addEnchant(Enchantment.SHARPNESS, 1, true);
        ench.setItemMeta(meta);

        ShopItem sword = new ShopItem("sword", Material.DIAMOND_SWORD, 1, 0,
                "", List.of(), BigDecimal.ZERO, new BigDecimal("10"),
                false, true, SellMatch.PLAIN_MATERIAL);
        Predicate<ItemStack> p = SellMatchPredicate.of(sword,
                new ItemStack(Material.DIAMOND_SWORD, 1), true);
        assertFalse(p.test(ench), "itemy z enchantami nie mogą być akceptowane przez PLAIN_MATERIAL");
    }

    @Test
    void pdcBlocksPlainMatch() {
        ItemStack pdcItem = new ItemStack(Material.DIAMOND);
        ItemMeta meta = pdcItem.getItemMeta();
        meta.getPersistentDataContainer().set(
                new NamespacedKey("hexnpc", "marker"), PersistentDataType.STRING, "custom");
        pdcItem.setItemMeta(meta);

        Predicate<ItemStack> p = SellMatchPredicate.of(plainItem(),
                new ItemStack(Material.DIAMOND, 1), true);
        assertFalse(p.test(pdcItem), "itemy z wpisem w PDC nie mogą być akceptowane przez PLAIN_MATERIAL");
    }
}
