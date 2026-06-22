package hexnpc.shop;

import hexnpc.shop.inventory.SellMatchPredicate;
import hexnpc.shop.inventory.ShopItemStackFactory;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.ShopItem;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.inventory.meta.EnchantedBookMetaMock;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Weryfikuje, że EXACT_ITEM faktycznie odrzuca kandydatów różniących
 * się specjalistycznymi polami meta (np. EnchantmentStorageMeta).
 * Wynik opiera się na łańcuchu: identyczność klasy meta + pełna
 * równość przez {@link org.bukkit.inventory.ItemFactory#equals}.
 */
class ExactItemSpecializedMetaTest {

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

    private ShopItem enchantedBookTemplateItem() {
        return new ShopItem(
                "book", Material.ENCHANTED_BOOK, 1, 0,
                "", List.of(),
                BigDecimal.ZERO, new BigDecimal("100"),
                false, true, SellMatch.EXACT_ITEM
        );
    }

    @Test
    void exactItemRejectsCandidateWithSpecializedMetaSubclass() {
        // MockBukkit nie tworzy automatycznie EnchantedBookMetaMock dla
        // ItemStacka z ENCHANTED_BOOK — szablon ma generic ItemMetaMock.
        // Wstrzykujemy kandydatowi konkretną klasę specjalistyczną
        // (EnchantedBookMetaMock z dodatkowym storedEnchant). Predykat
        // musi odrzucić przez sameConcreteMetaType.
        ShopItem item = enchantedBookTemplateItem();
        ItemStack template = ShopItemStackFactory.exactTemplate(item);
        Predicate<ItemStack> predicate = SellMatchPredicate.of(item, template, true);

        ItemStack candidate = new ItemStack(Material.ENCHANTED_BOOK, 1);
        EnchantedBookMetaMock specializedMeta = new EnchantedBookMetaMock();
        specializedMeta.addStoredEnchant(Enchantment.SHARPNESS, 3, true);
        candidate.setItemMeta(specializedMeta);

        assertFalse(predicate.test(candidate),
                "EXACT_ITEM musi odrzucić kandydata z inną konkretną klasą meta");
    }

    @Test
    void exactItemAcceptsCleanEnchantedBookCandidate() {
        ShopItem item = enchantedBookTemplateItem();
        ItemStack template = ShopItemStackFactory.exactTemplate(item);
        Predicate<ItemStack> predicate = SellMatchPredicate.of(item, template, true);

        // Czysty ENCHANTED_BOOK zbudowany przez tę samą fabrykę.
        ItemStack candidate = ShopItemStackFactory.exactTemplate(item);
        assertTrue(predicate.test(candidate),
                "EXACT_ITEM musi zaakceptować czysty stos z fabryki");
    }

    @Test
    void exactItemRejectsCandidateWithDifferentMetaClass() {
        // Gdyby ktoś zmajstrował kandydata z meta innej klasy niż
        // szablon — predykat musi to wyłapać przez sprawdzenie klasy.
        ShopItem item = new ShopItem(
                "diamond", Material.DIAMOND, 1, 0,
                "&bDiament", List.of("&7lore"),
                BigDecimal.ZERO, new BigDecimal("50"),
                false, true, SellMatch.EXACT_ITEM
        );
        ItemStack template = ShopItemStackFactory.exactTemplate(item);
        Predicate<ItemStack> predicate = SellMatchPredicate.of(item, template, true);

        // Czysty diament — meta jak template — akceptowany.
        ItemStack ok = ShopItemStackFactory.exactTemplate(item);
        assertTrue(predicate.test(ok),
                "kontrola pozytywna: czysty diament musi pasować");

        // Diament o innym Materialu (EMERALD) — odrzucany przez sam
        // type-check, niezależnie od meta.
        ItemStack wrongMat = new ItemStack(Material.EMERALD, 1);
        assertFalse(predicate.test(wrongMat),
                "EXACT_ITEM musi odrzucić item innego materiału");
    }
}
