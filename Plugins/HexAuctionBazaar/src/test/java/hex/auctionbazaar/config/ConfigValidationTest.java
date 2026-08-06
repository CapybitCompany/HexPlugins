package hex.auctionbazaar.config;

import hex.auctionbazaar.util.SafeTime;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #7/#8/#11: rzeczywista walidacja configu (przez {@link ConfigLoader#load}, nie helper w oderwaniu):
 * finalne, kolizyjnie-wolne sloty kontrolne + rewalidacja item-slotów; ochrona przed przepełnieniem terminów;
 * ceny Bazaru znormalizowane do DECIMAL(19,2); kategorie (0/1/5/&gt;5 + nieznana kategoria przedmiotu).
 */
class ConfigValidationTest {

    private final Logger log = Logger.getLogger("cfg-test");

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

    private PluginConfig load(String mainYaml, String itemsYaml) throws Exception {
        Path tmp = Files.createTempDirectory("hexab-val");
        if (itemsYaml != null) {
            Files.writeString(tmp.resolve("bazaar-items.yml"), itemsYaml);
        }
        YamlConfiguration main = new YamlConfiguration();
        main.loadFromString(mainYaml == null ? "" : mainYaml);
        return ConfigLoader.load(tmp.toFile(), main, log);
    }

    // ------------------------------------------------------------- sloty kontrolne (final)

    @Test
    void collidingControlSlotsResetWholeSetToSafeDefaults() throws Exception {
        // slot-refresh=45 koliduje z slot-prev-page (domyślnie 45). Ustawiamy też slot-empty-state=5,
        // aby udowodnić, że przy kolizji CAŁY zestaw wraca do bezpiecznych domyślnych (empty-state -> 22).
        PluginConfig c = load("auction:\n  gui:\n    slot-refresh: 45\n    slot-empty-state: 5\n", null);
        AuctionConfig a = c.auction();
        assertEquals(45, a.slotPrevPage());
        assertEquals(53, a.slotNextPage());
        assertEquals(49, a.slotRefresh(), "slot-refresh wrócił do bezpiecznego domyślnego");
        assertEquals(47, a.slotMyListings());
        assertEquals(51, a.slotClaims());
        assertEquals(48, a.slotSellHelp());
        assertEquals(50, a.slotSort());
        assertEquals(22, a.slotEmptyState(), "CAŁY zestaw zresetowany - także empty-state");
    }

    @Test
    void itemSlotsRevalidatedAgainstFinalControlSlots() throws Exception {
        // empty-state=5 (custom), a jednocześnie kolizja (refresh=45) -> reset -> empty-state=22.
        // item-slots=[22,...] koliduje z FINALNYM (zresetowanym) slotem 22 -> fallback do domyślnej siatki.
        PluginConfig c = load("auction:\n  gui:\n    slot-refresh: 45\n    slot-empty-state: 5\n"
                + "    item-slots: [22, 10, 12]\n", null);
        assertEquals(List.of(10, 12, 14, 16, 19, 21, 23, 25, 28, 30, 32, 34), c.auction().itemSlots(),
                "item-sloty zwalidowane względem FINALNYCH slotów kontrolnych");
    }

    @Test
    void noControlCollisionKeepsCustomSlots() throws Exception {
        // Bez kolizji custom sloty są zachowane (reset TYLKO przy kolizji).
        PluginConfig c = load("auction:\n  gui:\n    slot-empty-state: 4\n", null);
        assertEquals(4, c.auction().slotEmptyState());
        assertEquals(45, c.auction().slotPrevPage());
    }

    // ------------------------------------------------------------- przepełnienie terminów (#8)

    @Test
    void hugeDurationClampedToMaxNoOverflow() throws Exception {
        PluginConfig c = load("auction:\n  default-duration-seconds: " + Long.MAX_VALUE + "\n", null);
        assertEquals(ConfigLoader.MAX_DEADLINE_SECONDS, c.auction().defaultDurationSeconds());
    }

    @Test
    void nonPositiveDurationClampedToMin() throws Exception {
        PluginConfig c = load("auction:\n  default-duration-seconds: 0\n  reservation-ttl-seconds: -5\n", null);
        assertEquals(1L, c.auction().defaultDurationSeconds());
        assertEquals(1L, c.auction().reservationTtlSeconds());
    }

    @Test
    void safeTimeDeadlineNeverNegativeOnOverflow() {
        long now = System.currentTimeMillis();
        assertEquals(Long.MAX_VALUE, SafeTime.deadlineMillis(now, Long.MAX_VALUE), "przepełnienie -> MAX, nie ujemne");
        assertTrue(SafeTime.deadlineMillis(now, ConfigLoader.MAX_DEADLINE_SECONDS) > now, "wielki, ale dodatni termin");
        assertEquals(now, SafeTime.deadlineMillis(now, 0), "0 sekund -> teraz");
        assertEquals(now + 30_000L, SafeTime.deadlineMillis(now, 30));
    }

    // ------------------------------------------------------------- ceny Bazaru (#7)

    @Test
    void bazaarPricesNormalizedToTwoDecimals() throws Exception {
        PluginConfig c = load("", "items:\n"
                + "  test:\n"
                + "    material: STONE\n"
                + "    min-price: 1.239\n"
                + "    base-price: 2.005\n"
                + "    max-price: 9.991\n");
        var item = c.bazaar().item("test").orElseThrow();
        assertTrue(item.minPrice().scale() <= 2, "min znormalizowana do skali 2");
        assertTrue(item.basePrice().scale() <= 2, "base znormalizowana do skali 2");
        assertTrue(item.maxPrice().scale() <= 2, "max znormalizowana do skali 2");
        assertEquals(new java.math.BigDecimal("1.24"), item.minPrice());
    }

    @Test
    void bazaarPriceOverflowFallsBackAndFits() throws Exception {
        PluginConfig c = load("", "items:\n"
                + "  test:\n"
                + "    material: STONE\n"
                + "    max-price: 999999999999999999999\n");   // ponad DECIMAL(19,2)
        var item = c.bazaar().item("test").orElseThrow();
        assertTrue(hex.auctionbazaar.util.Money.fits(item.maxPrice()), "max mieści się w DECIMAL(19,2)");
    }

    // ------------------------------------------------------------- kategorie (#7)

    private static String categoriesYaml(int n) {
        StringBuilder sb = new StringBuilder("bazaar:\n  categories:\n");
        for (int i = 1; i <= n; i++) {
            sb.append("    cat").append(i).append(":\n")
                    .append("      display-name: \"Kat ").append(i).append("\"\n")
                    .append("      material: CHEST\n");
        }
        return sb.toString();
    }

    private static String itemInCategory(String category) {
        return "items:\n  gizmo:\n    material: STONE\n    category: " + category + "\n";
    }

    @Test
    void zeroCategoriesCreatesSafeDefaultSoItemStaysReachable() throws Exception {
        PluginConfig c = load("", itemInCategory("whatever"));
        assertEquals(1, c.bazaar().categories().size(), "domyślna kategoria utworzona");
        String defaultKey = c.bazaar().categories().keySet().iterator().next();
        assertEquals(defaultKey, c.bazaar().item("gizmo").orElseThrow().category(),
                "przedmiot przypisany do domyślnej (widocznej) kategorii");
    }

    @Test
    void oneCategoryVisible() throws Exception {
        PluginConfig c = load(categoriesYaml(1), itemInCategory("cat1"));
        assertEquals(1, c.bazaar().categories().size());
        assertEquals("cat1", c.bazaar().item("gizmo").orElseThrow().category());
    }

    @Test
    void fiveCategoriesAllVisible() throws Exception {
        PluginConfig c = load(categoriesYaml(5), itemInCategory("cat5"));
        assertEquals(5, c.bazaar().categories().size(), "dokładnie 5 mieści się w GUI");
        assertEquals("cat5", c.bazaar().item("gizmo").orElseThrow().category(), "widoczna kategoria zachowana");
    }

    @Test
    void moreThanFiveCategoriesCappedAndOverflowItemsReassigned() throws Exception {
        // 7 kategorii: widoczne pierwsze 5 (cat1..cat5). Przedmiot w cat7 (poza widocznymi) trafia do cat1.
        PluginConfig c = load(categoriesYaml(7), itemInCategory("cat7"));
        assertEquals(5, c.bazaar().categories().size(), "GUI pokazuje najwyżej 5 kategorii");
        assertEquals("cat1", c.bazaar().item("gizmo").orElseThrow().category(),
                "przedmiot spoza widocznych kategorii przypisany do pierwszej widocznej");
    }

    @Test
    void unknownItemCategoryReassignedToFirstVisible() throws Exception {
        PluginConfig c = load(categoriesYaml(3), itemInCategory("nieistnieje"));
        assertEquals("cat1", c.bazaar().item("gizmo").orElseThrow().category());
    }

    @Test
    void invalidCategoryMaterialFallsBackToChest() throws Exception {
        PluginConfig c = load("bazaar:\n  categories:\n    cat1:\n      display-name: \"K\"\n"
                + "      material: NOT_A_MATERIAL\n", itemInCategory("cat1"));
        assertEquals("CHEST", c.bazaar().categories().get("cat1").material(),
                "nieznany materiał kategorii -> bezpieczny CHEST");
    }
}
