package hexnpc.shop;

import hexnpc.shop.inventory.SellMatchPredicate;
import hexnpc.shop.inventory.ShopItemStackFactory;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.ShopItem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
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

/**
 * Dodatkowe testy EXACT_ITEM: kandydat z customizowaną metą
 * (PDC, damage, unbreakable) musi zostać odrzucony, nawet jeśli pola
 * wprost konfigurowane (nazwa, lore) się zgadzają.
 */
class ExactItemRejectionTest {

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

    private ShopItem swordItem() {
        return new ShopItem(
                "sword", Material.DIAMOND_SWORD, 1, 0,
                "&bMiecz", List.of("&7Ostry"),
                BigDecimal.ZERO, new BigDecimal("100"),
                false, true, SellMatch.EXACT_ITEM
        );
    }

    @Test
    void exactItemRejectsCandidateWithPdcEntry() {
        ShopItem item = swordItem();
        ItemStack template = ShopItemStackFactory.exactTemplate(item);
        Predicate<ItemStack> p = SellMatchPredicate.of(item, template, true);

        ItemStack candidate = ShopItemStackFactory.exactTemplate(item);
        ItemMeta meta = candidate.getItemMeta();
        meta.getPersistentDataContainer().set(
                new NamespacedKey("hexnpc-test", "marker"),
                PersistentDataType.STRING, "custom");
        candidate.setItemMeta(meta);

        assertFalse(p.test(candidate),
                "EXACT_ITEM musi odrzucić item z wpisem w PDC");
    }

    @Test
    void exactItemRejectsDamagedCandidate() {
        ShopItem item = swordItem();
        ItemStack template = ShopItemStackFactory.exactTemplate(item);
        Predicate<ItemStack> p = SellMatchPredicate.of(item, template, true);

        ItemStack candidate = ShopItemStackFactory.exactTemplate(item);
        ItemMeta meta = candidate.getItemMeta();
        if (meta instanceof Damageable damageable) {
            damageable.setDamage(50);
            candidate.setItemMeta(meta);
        }

        assertFalse(p.test(candidate),
                "EXACT_ITEM musi odrzucić item z uszkodzeniem");
    }

    @Test
    void exactItemRejectsUnbreakableCandidate() {
        ShopItem item = swordItem();
        ItemStack template = ShopItemStackFactory.exactTemplate(item);
        Predicate<ItemStack> p = SellMatchPredicate.of(item, template, true);

        ItemStack candidate = ShopItemStackFactory.exactTemplate(item);
        ItemMeta meta = candidate.getItemMeta();
        meta.setUnbreakable(true);
        candidate.setItemMeta(meta);

        assertFalse(p.test(candidate),
                "EXACT_ITEM musi odrzucić item oznaczony jako unbreakable");
    }

    @Test
    void exactItemAcceptsCleanCandidate() {
        ShopItem item = swordItem();
        ItemStack template = ShopItemStackFactory.exactTemplate(item);
        Predicate<ItemStack> p = SellMatchPredicate.of(item, template, true);

        // Brak zmian poza tym, co konfiguruje template -> akceptujemy.
        ItemStack candidate = ShopItemStackFactory.exactTemplate(item);
        assertTrue(p.test(candidate),
                "EXACT_ITEM musi zaakceptować czysty stos zbudowany z tej samej fabryki");
    }
}
