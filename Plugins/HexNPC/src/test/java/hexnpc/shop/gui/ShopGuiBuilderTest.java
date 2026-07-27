package hexnpc.shop.gui;

import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.economy.EconomyBridge;
import hexnpc.shop.model.PlacementMode;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import hexnpc.shop.model.ShopLayout;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Widok GUI: przyciski nawigacji pojawiają się tylko gdy strona istnieje,
 * numer strony jest ograniczany, a widok szczegółów przechowuje stronę
 * źródłową, wybraną ilość oraz oznacza kupno ponad limitem.
 */
class ShopGuiBuilderTest {

    private ServerMock server;
    private ShopGuiBuilder builder;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        // MockBukkit nie implementuje createInventory z tytułem Component —
        // podstawiamy wariant z tytułem String (legacy §).
        ShopGuiBuilder.setInventoryFactory((h, s, t) ->
                Bukkit.createInventory(h, s, LegacyComponentSerializer.legacySection().serialize(t)));
        builder = new ShopGuiBuilder(ShopConfig.defaults(), new EconomyBridge(Logger.getLogger("t")));
    }

    @AfterEach
    void tearDown() {
        ShopGuiBuilder.setInventoryFactory(null);
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private ShopLayout twoSlotLayout() {
        return new ShopLayout(27, PlacementMode.AUTO, List.of(10, 11), 18, 22, 26,
                Material.GRAY_STAINED_GLASS_PANE, " ", 4, 13, List.of(19, 21, 23), 25, 29, 33, 31, 22)
                .validated(null, "test");
    }

    private ShopItem plain(String id, Material mat, int maxBuy) {
        return new ShopItem(id, mat, 1, ShopItem.NO_SLOT, 0, "", List.of(),
                new BigDecimal("10"), new BigDecimal("5"), true, true, SellMatch.PLAIN_MATERIAL, maxBuy);
    }

    private Shop threeItemShop(ShopLayout layout) {
        Map<String, ShopItem> map = new LinkedHashMap<>();
        map.put("a", plain("a", Material.STONE, 0));
        map.put("b", plain("b", Material.DIRT, 0));
        map.put("c", plain("c", Material.SAND, 0));
        return new Shop("test", "&8Test", layout, map);
    }

    private ShopGuiHolder holder(Inventory inv) {
        return (ShopGuiHolder) inv.getHolder();
    }

    /** Sklep na domyślnym układzie 54 — sloty elementów są znane i stałe. */
    private Shop defaultShop() {
        Map<String, ShopItem> map = new LinkedHashMap<>();
        map.put("a", plain("a", Material.STONE, 0));
        map.put("b", plain("b", Material.DIRT, 0));
        return new Shop("test", "&8Test", ShopLayout.defaults(54), map);
    }

    @Test
    void mainFillerHidesTooltipButInteractiveElementsKeepIt() {
        Shop shop = defaultShop();
        Inventory inv = builder.buildMain(shop, 0);
        // Slot 0 to dekoracyjny wypełniacz tła.
        ItemMeta filler = inv.getItem(0).getItemMeta();
        assertNotNull(filler);
        assertTrue(filler.isHideTooltip(), "wypełniacz tła musi ukrywać dymek");
        // Shopartikel na pierwszym item-slocie (10) zachowuje dymek.
        ItemMeta item = inv.getItem(10).getItemMeta();
        assertNotNull(item);
        assertFalse(item.isHideTooltip(), "przedmiot sklepu musi zachować dymek");
        // Wskaźnik strony (page-slot 49) zachowuje dymek.
        ItemMeta pageInfo = inv.getItem(49).getItemMeta();
        assertNotNull(pageInfo);
        assertFalse(pageInfo.isHideTooltip(), "wskaźnik strony musi zachować dymek");
    }

    @Test
    void detailFillerHidesTooltipButButtonsKeepIt() {
        Shop shop = defaultShop();
        ShopItem item = shop.item("a").orElseThrow();
        Inventory inv = builder.buildDetail(shop, item, 1, 0, Integer.MAX_VALUE, SellAllQuote.empty());
        // Slot 0 to wypełniacz.
        assertTrue(inv.getItem(0).getItemMeta().isHideTooltip(), "wypełniacz tła musi ukrywać dymek");
        // Przyciski i podgląd zachowują dymek.
        assertFalse(inv.getItem(shop.layout().detailBuySlot()).getItemMeta().isHideTooltip(),
                "przycisk kupna musi zachować dymek");
        assertFalse(inv.getItem(shop.layout().detailPreviewSlot()).getItemMeta().isHideTooltip(),
                "podgląd przedmiotu musi zachować dymek");
        assertFalse(inv.getItem(shop.layout().detailBackSlot()).getItemMeta().isHideTooltip(),
                "przycisk powrotu musi zachować dymek");
    }

    @Test
    void firstPageHasNextButNoPrevious() {
        Shop shop = threeItemShop(twoSlotLayout()); // 3 itemy / 2 sloty = 2 strony
        Inventory inv = builder.buildMain(shop, 0);
        ShopGuiHolder h = holder(inv);
        assertEquals(0, h.page());
        assertEquals(2, h.totalPages());
        // Poprzednia strona nie istnieje -> slot 18 to wypełniacz, nie strzałka.
        assertNotEquals(Material.ARROW, inv.getItem(18).getType());
        // Następna strona istnieje -> strzałka na slocie 26.
        assertEquals(Material.ARROW, inv.getItem(26).getType());
    }

    @Test
    void lastPageHasPreviousButNoNext() {
        Shop shop = threeItemShop(twoSlotLayout());
        Inventory inv = builder.buildMain(shop, 1);
        assertEquals(Material.ARROW, inv.getItem(18).getType());
        assertNotEquals(Material.ARROW, inv.getItem(26).getType());
    }

    @Test
    void invalidPageIsClampedInBuiltHolder() {
        Shop shop = threeItemShop(twoSlotLayout());
        Inventory inv = builder.buildMain(shop, 999);
        assertEquals(1, holder(inv).page(), "za wysoka strona musi zostać ograniczona do ostatniej");
    }

    @Test
    void detailStoresOriginPageAndSelectedQuantity() {
        Shop shop = threeItemShop(twoSlotLayout());
        ShopItem item = shop.item("a").orElseThrow();
        Inventory inv = builder.buildDetail(shop, item, 32, 1, Integer.MAX_VALUE, SellAllQuote.empty());
        ShopGuiHolder h = holder(inv);
        assertEquals(1, h.originPage(), "widok szczegółów musi pamiętać stronę źródłową");
        assertEquals(32, h.selectedQuantity());
        assertTrue(h.buyButtonSlot() >= 0);
        assertTrue(h.sellButtonSlot() >= 0);
        // Domyślne presety to [1, 64] -> renderowane 2 przyciski (mimo 3 slotów).
        assertEquals(2, h.presetSlots().size());
        assertTrue(h.presetSlots().containsValue(1));
        assertTrue(h.presetSlots().containsValue(64));
    }

    @Test
    void buyButtonBecomesBarrierWhenOverDailyLimit() {
        Map<String, ShopItem> map = new LinkedHashMap<>();
        map.put("d", plain("d", Material.DIAMOND, 10)); // limit 10/dzień
        Shop shop = new Shop("test", "&8Test", twoSlotLayout(), map);
        ShopItem item = shop.item("d").orElseThrow();
        // Wybrana ilość 100 > pozostały limit 5 -> przycisk kupna = BARRIER.
        Inventory inv = builder.buildDetail(shop, item, 100, 0, 5, SellAllQuote.empty());
        int buySlot = shop.layout().detailBuySlot();
        assertEquals(Material.BARRIER, inv.getItem(buySlot).getType());
    }
}
