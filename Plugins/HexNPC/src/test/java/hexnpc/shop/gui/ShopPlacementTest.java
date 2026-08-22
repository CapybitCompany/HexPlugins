package hexnpc.shop.gui;

import hexnpc.shop.model.PlacementMode;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import hexnpc.shop.model.ShopLayout;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paginacja i rozmieszczenie: jedna/kilka stron, pierwsza/środkowa/ostatnia,
 * ograniczanie numeru strony oraz tryby AUTO i MANUAL.
 */
class ShopPlacementTest {

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

    /** Układ AUTO z dwoma slotami itemów na stronę (łatwy do paginacji). */
    private ShopLayout twoSlotAutoLayout() {
        return new ShopLayout(27, PlacementMode.AUTO, List.of(10, 11), 18, 22, 26,
                Material.GRAY_STAINED_GLASS_PANE, " ", 0, 1, List.of(2, 3), 4, 5, 6, 7, 8)
                .validated(null, "test");
    }

    private ShopItem autoItem(String id, Material material) {
        return new ShopItem(id, material, 1, ShopItem.NO_SLOT, 0, "", List.of(),
                new BigDecimal("10"), new BigDecimal("5"), true, true, SellMatch.PLAIN_MATERIAL, 0);
    }

    private ShopItem manualItem(String id, Material material, int slot, int page) {
        return new ShopItem(id, material, 1, slot, page, "", List.of(),
                new BigDecimal("10"), new BigDecimal("5"), true, true, SellMatch.PLAIN_MATERIAL, 0);
    }

    private Shop autoShop(ShopLayout layout, ShopItem... items) {
        Map<String, ShopItem> map = new LinkedHashMap<>();
        for (ShopItem item : items) {
            map.put(item.id(), item);
        }
        return new Shop("test", "&8Test", layout, map);
    }

    @Test
    void singlePageWhenFewItems() {
        Shop shop = autoShop(twoSlotAutoLayout(), autoItem("a", Material.STONE));
        assertEquals(1, ShopPlacement.totalPages(shop));
        Map<Integer, ShopItem> page0 = ShopPlacement.itemsForPage(shop, 0);
        assertEquals(1, page0.size());
        assertTrue(page0.containsKey(10));
    }

    @Test
    void multiplePagesDistributeInOrder() {
        Shop shop = autoShop(twoSlotAutoLayout(),
                autoItem("a", Material.STONE),
                autoItem("b", Material.DIRT),
                autoItem("c", Material.OAK_LOG),
                autoItem("d", Material.COBBLESTONE),
                autoItem("e", Material.SAND));
        // 5 itemów / 2 sloty = 3 strony.
        assertEquals(3, ShopPlacement.totalPages(shop));

        // Pierwsza strona: a, b.
        Map<Integer, ShopItem> first = ShopPlacement.itemsForPage(shop, 0);
        assertEquals(2, first.size());
        assertEquals("a", first.get(10).id());
        assertEquals("b", first.get(11).id());

        // Środkowa strona: c, d.
        Map<Integer, ShopItem> middle = ShopPlacement.itemsForPage(shop, 1);
        assertEquals(2, middle.size());
        assertEquals("c", middle.get(10).id());
        assertEquals("d", middle.get(11).id());

        // Ostatnia strona: e (jeden item).
        Map<Integer, ShopItem> last = ShopPlacement.itemsForPage(shop, 2);
        assertEquals(1, last.size());
        assertEquals("e", last.get(10).id());
    }

    @Test
    void invalidPageIsClamped() {
        Shop shop = autoShop(twoSlotAutoLayout(),
                autoItem("a", Material.STONE),
                autoItem("b", Material.DIRT),
                autoItem("c", Material.OAK_LOG));
        int total = ShopPlacement.totalPages(shop); // 2
        assertEquals(0, ShopPlacement.clampPage(-5, total));
        assertEquals(total - 1, ShopPlacement.clampPage(999, total));
        assertEquals(1, ShopPlacement.clampPage(1, total));
    }

    @Test
    void manualPlacementUsesSlotAndPage() {
        ShopLayout layout = new ShopLayout(27, PlacementMode.MANUAL, List.of(10, 11), 18, 22, 26,
                Material.GRAY_STAINED_GLASS_PANE, " ", 0, 1, List.of(2, 3), 4, 5, 6, 7, 8)
                .validated(null, "test");
        Shop shop = autoShop(layout,
                manualItem("a", Material.STONE, 5, 0),
                manualItem("b", Material.DIRT, 7, 0),
                manualItem("c", Material.SAND, 3, 1));
        assertEquals(2, ShopPlacement.totalPages(shop));

        Map<Integer, ShopItem> page0 = ShopPlacement.itemsForPage(shop, 0);
        assertEquals(2, page0.size());
        assertEquals("a", page0.get(5).id());
        assertEquals("b", page0.get(7).id());

        Map<Integer, ShopItem> page1 = ShopPlacement.itemsForPage(shop, 1);
        assertEquals(1, page1.size());
        assertEquals("c", page1.get(3).id());
    }

    @Test
    void emptyShopHasOnePage() {
        Shop shop = autoShop(twoSlotAutoLayout());
        assertEquals(1, ShopPlacement.totalPages(shop));
        assertTrue(ShopPlacement.itemsForPage(shop, 0).isEmpty());
    }
}
