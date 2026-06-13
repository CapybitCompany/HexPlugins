package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.service.PlainItemMatcher;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainItemMatcherTest {

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

    @Test
    void plainStackPasses() {
        ItemStack stack = new ItemStack(Material.DIAMOND, 3);
        assertTrue(PlainItemMatcher.isPlain(stack, Material.DIAMOND));
    }

    @Test
    void wrongMaterialRejected() {
        ItemStack stack = new ItemStack(Material.IRON_INGOT, 1);
        assertFalse(PlainItemMatcher.isPlain(stack, Material.DIAMOND));
    }

    @Test
    void zeroAmountRejected() {
        ItemStack stack = new ItemStack(Material.DIAMOND);
        stack.setAmount(0);
        assertFalse(PlainItemMatcher.isPlain(stack, Material.DIAMOND));
    }

    @Test
    void customDisplayNameRejected() {
        ItemStack stack = new ItemStack(Material.DIAMOND, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("Custom"));
        stack.setItemMeta(meta);
        assertFalse(PlainItemMatcher.isPlain(stack, Material.DIAMOND));
    }

    @Test
    void customLoreRejected() {
        ItemStack stack = new ItemStack(Material.DIAMOND, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.lore(List.of(net.kyori.adventure.text.Component.text("rare")));
        stack.setItemMeta(meta);
        assertFalse(PlainItemMatcher.isPlain(stack, Material.DIAMOND));
    }

    @Test
    void enchantedRejected() {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD, 1);
        stack.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 1);
        assertFalse(PlainItemMatcher.isPlain(stack, Material.DIAMOND_SWORD));
    }
}
