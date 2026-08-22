package hexnpc.shop;

import hexnpc.shop.inventory.InventoryOps;
import hexnpc.shop.inventory.SellMatchPredicate;
import hexnpc.shop.inventory.ShopItemStackFactory;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.ShopItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprawdza, że tradeStack/exactTemplate/displayStack zachowują się
 * zgodnie z umową: gracz może odsprzedać zakupiony stos PLAIN_MATERIAL
 * z powrotem do tego samego sklepu, a EXACT_ITEM dalej odrzuca itemy
 * z customizowaną metą.
 */
class StackFactoryAndRoundTripTest {

    private ServerMock server;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        player = server.addPlayer("Tester");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private ShopItem plainItemWithDisplayConfig() {
        return new ShopItem(
                "cobblestone",
                Material.COBBLESTONE, 64, 10,
                "&7Bruk",
                List.of("&8Podstawowy blok budowlany"),
                new BigDecimal("100"),
                new BigDecimal("25"),
                true, true, SellMatch.PLAIN_MATERIAL
        );
    }

    @Test
    void tradeStackForPlainMaterialIsTrulyPlain() {
        ShopItem item = plainItemWithDisplayConfig();
        ItemStack trade = ShopItemStackFactory.tradeStack(item);
        assertNotNull(trade);
        // Item wydany graczowi musi być vanilla — bez DisplayName i lore.
        if (trade.hasItemMeta()) {
            var meta = trade.getItemMeta();
            assertFalse(meta.hasDisplayName(),
                    "tradeStack dla PLAIN_MATERIAL nie może mieć display name");
            assertFalse(meta.hasLore(),
                    "tradeStack dla PLAIN_MATERIAL nie może mieć lore");
        }
        // Predykat PLAIN_MATERIAL musi taki stos zaakceptować.
        Predicate<ItemStack> p = SellMatchPredicate.of(item,
                ShopItemStackFactory.exactTemplate(item), true);
        assertTrue(p.test(trade),
                "stos kupiony przez gracza musi przejść własny PLAIN_MATERIAL predykat");
    }

    @Test
    void boughtPlainItemSurvivesInventoryRoundTrip() {
        ShopItem item = plainItemWithDisplayConfig();
        // Symulujemy stronę buy: dodajemy tradeStack do ekwipunku.
        ItemStack trade = ShopItemStackFactory.tradeStack(item);
        assertTrue(InventoryOps.giveAllOrNothing(player, trade),
                "tradeStack powinien zmieścić się do pustego ekwipunku");

        // Symulujemy stronę sell: predykat PLAIN_MATERIAL musi zliczyć
        // ten stos i usunąć wymagany amount.
        Predicate<ItemStack> p = SellMatchPredicate.of(item,
                ShopItemStackFactory.exactTemplate(item), true);
        int count = InventoryOps.countMatching(player.getInventory(), p);
        assertTrue(count >= item.amount(),
                "zakupiony stos musi być sprzedawalny w tym samym sklepie; count=" + count);
        var removed = InventoryOps.removeAllOrNothing(player.getInventory(), p, item.amount());
        assertTrue(removed.isPresent(),
                "removeAllOrNothing musi się udać dla zakupionego itemu PLAIN_MATERIAL");
    }

    @Test
    void exactTemplateForExactItemKeepsConfiguredMeta() {
        ShopItem rare = new ShopItem(
                "rare", Material.DIAMOND, 1, 0,
                "&bRzadki Diament", List.of("&7Specjalny"),
                BigDecimal.ZERO, new BigDecimal("500"),
                false, true, SellMatch.EXACT_ITEM
        );
        ItemStack template = ShopItemStackFactory.exactTemplate(rare);
        assertNotNull(template);
        assertTrue(template.hasItemMeta());
        assertTrue(template.getItemMeta().hasDisplayName(),
                "exactTemplate musi nieść skonfigurowane display name");
        assertTrue(template.getItemMeta().hasLore(),
                "exactTemplate musi nieść skonfigurowane lore");

        // tradeStack dla EXACT_ITEM też musi nieść metę — gracz odsprzeda
        // ten sam item z powrotem.
        ItemStack trade = ShopItemStackFactory.tradeStack(rare);
        assertTrue(trade.hasItemMeta());
        assertTrue(trade.getItemMeta().hasDisplayName(),
                "tradeStack dla EXACT_ITEM musi nieść meta");
    }
}
