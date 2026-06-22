package hexnpc.shop;

import hexnpc.shop.inventory.SellMatchPredicate;
import hexnpc.shop.inventory.ShopItemStackFactory;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.ShopItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactItemMatchTest {

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

    private ShopItem namedDiamond() {
        return new ShopItem(
                "named-diamond", Material.DIAMOND, 1, 0,
                "&bRzadki Diament",
                List.of("&7Specjalne lore"),
                BigDecimal.ZERO, new BigDecimal("100"),
                false, true, SellMatch.EXACT_ITEM
        );
    }

    @Test
    void exactItemRequiresConfiguredDisplayNameAndLore() {
        ShopItem item = namedDiamond();
        ItemStack template = ShopItemStackFactory.exactTemplate(item);
        ItemMeta templateMeta = template.getItemMeta();
        assertNotNull(templateMeta);
        assertTrue(templateMeta.hasDisplayName(),
                "fabryka musi nadać skonfigurowane display name");
        assertTrue(templateMeta.hasLore(),
                "fabryka musi nadać skonfigurowane lore");

        Predicate<ItemStack> predicate = SellMatchPredicate.of(item, template, true);

        // Goły diament — nieprawidłowa meta, nie może pasować EXACT_ITEM.
        assertFalse(predicate.test(new ItemStack(Material.DIAMOND, 1)),
                "goły diament nie może pasować do EXACT_ITEM ze skonfigurowaną nazwą/lore");

        // Ta sama nazwa + lore co skonfigurowane — musi pasować.
        ItemStack candidate = ShopItemStackFactory.exactTemplate(item);
        candidate.setAmount(2);
        assertTrue(predicate.test(candidate),
                "stos zbudowany przez tę samą fabrykę musi pasować do EXACT_ITEM");
    }

    @Test
    void exactItemRejectsDifferentDisplayName() {
        ShopItem item = namedDiamond();
        ItemStack template = ShopItemStackFactory.exactTemplate(item);
        Predicate<ItemStack> predicate = SellMatchPredicate.of(item, template, true);

        ItemStack imposter = new ItemStack(Material.DIAMOND, 1);
        ItemMeta meta = imposter.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("Fałszywy Diament"));
        imposter.setItemMeta(meta);

        assertFalse(predicate.test(imposter),
                "EXACT_ITEM musi porównać display name");
    }
}
