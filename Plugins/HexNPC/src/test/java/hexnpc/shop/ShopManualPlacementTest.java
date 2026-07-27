package hexnpc.shop;

import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.gui.ShopPlacement;
import hexnpc.shop.model.PlacementMode;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walidacja MANUAL (kolizje z nawigacją, duplikaty page/slot, ten sam slot na
 * różnych stronach), priorytet rozstrzygania placement oraz to, że AUTO
 * ignoruje slot i page. Ostrzeżenia są przechwytywane z loggera.
 */
class ShopManualPlacementTest {

    private ServerMock server;
    private Logger logger;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        logger = Logger.getLogger("manual-placement-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        warnings.clear();
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Shop load(String yaml) throws Exception {
        ShopRegistry registry = new ShopRegistry(logger);
        registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults());
        return registry.all().iterator().next();
    }

    private boolean warningContains(String... parts) {
        for (String w : warnings) {
            boolean all = true;
            for (String p : parts) {
                if (!w.contains(p)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    // --- Kolizje MANUAL (rozmiar 27 => nawigacja 18/22/26) ---

    @Test
    void manualItemsOnNavigationSlotsAreSkipped() throws Exception {
        Shop shop = load("""
                shops:
                  m:
                    size: 27
                    placement: MANUAL
                    items:
                      good:   { material: STONE,  amount: 1, slot: 10, page: 0, buy-price: "1" }
                      onprev: { material: DIRT,   amount: 1, slot: 18, page: 0, buy-price: "1" }
                      onpage: { material: SAND,   amount: 1, slot: 22, page: 0, buy-price: "1" }
                      onnext: { material: OAK_LOG,amount: 1, slot: 26, page: 0, buy-price: "1" }
                """);
        assertTrue(shop.item("good").isPresent());
        assertTrue(shop.item("onprev").isEmpty(), "slot previous-slot musi być odrzucony");
        assertTrue(shop.item("onpage").isEmpty(), "slot page-slot musi być odrzucony");
        assertTrue(shop.item("onnext").isEmpty(), "slot next-slot musi być odrzucony");
        // Ostrzeżenie musi zawierać ID sklepu, ID itemu, stronę i slot.
        assertTrue(warningContains("'m'", "'onpage'", "slot 22", "stronie 0", "nawigacji"),
                "ostrzeżenie o kolizji z nawigacją musi być czytelne; było: " + warnings);
    }

    @Test
    void duplicatePageSlotSkipsLaterItemButAllowsDifferentPage() throws Exception {
        Shop shop = load("""
                shops:
                  m:
                    size: 27
                    placement: MANUAL
                    items:
                      dupa:      { material: COAL,     amount: 1, slot: 5, page: 0, buy-price: "1" }
                      dupb:      { material: CHARCOAL, amount: 1, slot: 5, page: 0, buy-price: "1" }
                      otherpage: { material: PAPER,    amount: 1, slot: 5, page: 1, buy-price: "1" }
                """);
        assertTrue(shop.item("dupa").isPresent(), "pierwszy z pary page:slot pozostaje");
        assertTrue(shop.item("dupb").isEmpty(), "duplikat page:slot musi być pominięty");
        assertTrue(shop.item("otherpage").isPresent(), "ten sam slot na innej stronie jest dozwolony");
        assertTrue(warningContains("'dupb'", "slot 5", "stronie 0", "zajęty"),
                "ostrzeżenie o duplikacie musi być czytelne; było: " + warnings);
    }

    // --- Priorytet placement ---

    @Test
    void rootPlacementWins() throws Exception {
        Shop shop = load("""
                shops:
                  s:
                    size: 27
                    placement: MANUAL
                    items:
                      a: { material: STONE, amount: 1, slot: 10, buy-price: "1" }
                """);
        assertEquals(PlacementMode.MANUAL, shop.layout().placement());
    }

    @Test
    void layoutPlacementUsedWhenNoRoot() throws Exception {
        Shop shop = load("""
                shops:
                  s:
                    size: 27
                    layout:
                      placement: MANUAL
                    items:
                      a: { material: STONE, amount: 1, slot: 10, buy-price: "1" }
                """);
        assertEquals(PlacementMode.MANUAL, shop.layout().placement());
    }

    @Test
    void rootPlacementBeatsLayoutPlacementOnConflict() throws Exception {
        Shop shop = load("""
                shops:
                  s:
                    size: 27
                    placement: AUTO
                    layout:
                      placement: MANUAL
                    items:
                      a: { material: STONE, amount: 1, slot: 10, buy-price: "1" }
                """);
        assertEquals(PlacementMode.AUTO, shop.layout().placement(), "root placement musi wygrać");
        assertTrue(warningContains("różnią się"), "konflikt placement musi być zalogowany; było: " + warnings);
    }

    @Test
    void explicitItemSlotInfersManual() throws Exception {
        Shop shop = load("""
                shops:
                  s:
                    size: 27
                    items:
                      a: { material: STONE, amount: 1, slot: 10, buy-price: "1" }
                """);
        assertEquals(PlacementMode.MANUAL, shop.layout().placement());
    }

    @Test
    void noPlacementNoSlotsUsesGlobalDefault() throws Exception {
        Shop shop = load("""
                shops:
                  s:
                    size: 27
                    items:
                      a: { material: STONE, amount: 1, buy-price: "1" }
                """);
        // Globalny default z ShopConfig.defaults() to AUTO.
        assertEquals(PlacementMode.AUTO, shop.layout().placement());
    }

    @Test
    void invalidPlacementFallsBackWithWarning() throws Exception {
        Shop shop = load("""
                shops:
                  s:
                    size: 27
                    placement: SIDEWAYS
                    items:
                      a: { material: STONE, amount: 1, slot: 10, buy-price: "1" }
                """);
        // Nieprawidłowe -> ostrzeżenie, potem fallback (item ma slot => MANUAL).
        assertEquals(PlacementMode.MANUAL, shop.layout().placement());
        assertTrue(warningContains("SIDEWAYS"), "nieprawidłowy placement musi być zalogowany; było: " + warnings);
    }

    // --- AUTO ignoruje slot i page ---

    // --- Zakomentowane przykłady z shops.yml muszą być prawidłowe po odkomentowaniu ---

    @Test
    void commentedManualExampleLoadsCleanly() throws Exception {
        Shop shop = load("""
                shops:
                  kopalnia:
                    title: "&8Kopalnia"
                    size: 27
                    placement: MANUAL
                    layout:
                      navigation:
                        previous-slot: 18
                        page-slot: 22
                        next-slot: 26
                    items:
                      coal:       { material: COAL,       amount: 64, slot: 10, page: 0, buy-price: "50.00", sell-price: "20.00" }
                      gold_ingot: { material: GOLD_INGOT, amount: 8,  slot: 16, page: 0, buy-price: "120.00", sell-price: "60.00" }
                      iron_ingot: { material: IRON_INGOT, amount: 16, slot: 10, page: 1, buy-price: "80.00", sell-price: "40.00" }
                """);
        assertEquals(PlacementMode.MANUAL, shop.layout().placement());
        assertTrue(shop.item("coal").isPresent());
        assertTrue(shop.item("gold_ingot").isPresent());
        assertTrue(shop.item("iron_ingot").isPresent(), "ten sam slot na innej stronie jest dozwolony");
        assertEquals(2, ShopPlacement.totalPages(shop));
        assertTrue(warnings.isEmpty(), "poprawny przykład nie może generować ostrzeżeń; było: " + warnings);
    }

    @Test
    void commentedLayoutOverrideExampleLoadsCleanly() throws Exception {
        Shop shop = load("""
                shops:
                  własny_sklep:
                    title: "&8Własny sklep"
                    size: 54
                    placement: AUTO
                    layout:
                      item-slots: [10, 12, 14, 16, 28, 30, 32, 34]
                      navigation: { previous-slot: 45, page-slot: 49, next-slot: 53 }
                      filler: { material: BLACK_STAINED_GLASS_PANE, name: " " }
                      detail:
                        preview-slot: 4
                        selected-info-slot: 13
                        quantity-slots: [19, 21, 23]
                        custom-quantity-slot: 25
                        buy-slot: 38
                        sell-all-slot: 40
                        sell-slot: 42
                        back-slot: 49
                    items:
                      emerald: { material: EMERALD, amount: 1, buy-price: "300.00", sell-price: "150.00" }
                """);
        assertEquals(PlacementMode.AUTO, shop.layout().placement());
        assertEquals(List.of(10, 12, 14, 16, 28, 30, 32, 34), shop.layout().itemSlots());
        assertTrue(shop.item("emerald").isPresent());
        assertTrue(warnings.isEmpty(), "poprawny przykład nie może generować ostrzeżeń; było: " + warnings);
    }

    @Test
    void autoIgnoresSlotAndPage() throws Exception {
        Shop shop = load("""
                shops:
                  s:
                    size: 27
                    placement: AUTO
                    items:
                      a: { material: STONE, amount: 1, slot: 5, page: 2, buy-price: "1" }
                """);
        ShopItem item = shop.item("a").orElseThrow();
        assertEquals(ShopItem.NO_SLOT, item.slot(), "AUTO ignoruje slot");
        assertEquals(0, item.page(), "AUTO ignoruje page");
        // Item ląduje na pierwszym item-slocie układu, nie na 'slot: 5'.
        assertEquals(1, ShopPlacement.totalPages(shop));
        assertFalse(ShopPlacement.itemsForPage(shop, 0).containsKey(5),
                "AUTO nie może użyć jawnego slotu 5");
        assertTrue(ShopPlacement.itemsForPage(shop, 0).containsValue(item));
    }
}
