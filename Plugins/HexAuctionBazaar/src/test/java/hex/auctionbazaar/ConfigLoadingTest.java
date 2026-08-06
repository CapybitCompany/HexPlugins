package hex.auctionbazaar;

import hex.auctionbazaar.config.ConfigLoader;
import hex.auctionbazaar.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkty #3/#4/#8/#9/#10: ładowanie konfiguracji.
 * Domyślne tytuły „Rynek”, auto-refresh domyślnie wyłączony, podatek 10%,
 * progi rang, legacy-fallback limitu, walidacja podatku i slotów.
 */
class ConfigLoadingTest {

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

    private PluginConfig load(String yaml) throws Exception {
        Path tmp = Files.createTempDirectory("hexab-cfg");
        YamlConfiguration main = new YamlConfiguration();
        main.loadFromString(yaml == null ? "" : yaml);
        return ConfigLoader.load(tmp.toFile(), main, Logger.getLogger("test"));
    }

    private PluginConfig loadWithBazaarItems(String itemsYaml) throws Exception {
        Path tmp = Files.createTempDirectory("hexab-items-cfg");
        Files.writeString(tmp.resolve("bazaar-items.yml"), itemsYaml);
        YamlConfiguration main = new YamlConfiguration();
        main.loadFromString("");
        return ConfigLoader.load(tmp.toFile(), main, Logger.getLogger("test"));
    }

    @Test
    void javaDefaultsUseRynekAndSafeValues() throws Exception {
        PluginConfig c = load("");
        assertEquals("&8&lRynek", c.bazaar().guiTitle(), "domyślny tytuł Bazaru = Rynek");
        assertFalse(c.bazaar().autoRefreshEnabled(), "auto-refresh domyślnie wyłączony");
        assertEquals(new BigDecimal("10"), c.auction().saleFeePercent(), "domyślny podatek 10%");
        assertEquals(10, c.auction().listingLimitDefault());
    }

    @Test
    void legacyMaxListingsUsedWhenNoTierSection() throws Exception {
        PluginConfig c = load("auction:\n  max-active-listings-per-player: 7\n");
        assertEquals(7, c.auction().listingLimitDefault(), "legacy fallback limitu");
        assertTrue(c.auction().listingLimitTiers().isEmpty());
    }

    @Test
    void listingLimitTiersLoaded() throws Exception {
        String yaml = "auction:\n" +
                "  listing-limits:\n" +
                "    default: 12\n" +
                "    tiers:\n" +
                "      vip:\n" +
                "        permission: \"hexauction.limit.vip\"\n" +
                "        max-active-listings: 25\n";
        PluginConfig c = load(yaml);
        assertEquals(12, c.auction().listingLimitDefault());
        assertEquals(1, c.auction().listingLimitTiers().size());
        assertEquals(25, c.auction().listingLimitTiers().get(0).maxActiveListings());
        assertEquals("hexauction.limit.vip", c.auction().listingLimitTiers().get(0).permission());
    }

    @Test
    void saleFeeTiersLoaded() throws Exception {
        String yaml = "auction:\n" +
                "  sale-fee-percent: 10\n" +
                "  sale-fee-tiers:\n" +
                "    vip:\n" +
                "      permission: \"hexauction.tax.vip\"\n" +
                "      sale-fee-percent: 8\n";
        PluginConfig c = load(yaml);
        assertEquals(1, c.auction().saleFeeTiers().size());
        assertEquals(new BigDecimal("8"), c.auction().saleFeeTiers().get(0).percent());
    }

    @Test
    void invalidTaxPercentClampedToRange() throws Exception {
        assertEquals(new BigDecimal("100"),
                load("auction:\n  sale-fee-percent: 150\n").auction().saleFeePercent());
        assertEquals(BigDecimal.ZERO,
                load("auction:\n  sale-fee-percent: -5\n").auction().saleFeePercent());
    }

    @Test
    void collidingPagedSlotsFallBackToDefaults() throws Exception {
        // paged-slot-back=10 wpada w obszar przedmiotów -> bezpieczne domyślne (45).
        PluginConfig c = load("auction:\n  gui:\n    paged-slot-back: 10\n");
        assertEquals(45, c.auction().pagedSlotBack());
        assertEquals(48, c.auction().pagedSlotPrevPage());
        assertEquals(50, c.auction().pagedSlotNextPage());
        assertEquals(49, c.auction().pagedSlotPageInfo());
    }

    @Test
    void legacySellPricePresetsKeyIsIgnored() throws Exception {
        // Stary klucz nie może wywrócić startu - jest po prostu ignorowany.
        PluginConfig c = load("auction:\n  sell-price-presets: [100, 500]\n");
        assertEquals(new BigDecimal("10"), c.auction().saleFeePercent());
    }

    // ---- #1 database ----

    @Test
    void databaseDefaultsToHexcore() throws Exception {
        PluginConfig c = load("");
        assertEquals("HEXCORE", c.database().provider());
        assertTrue(c.database().usesHexCore());
        assertTrue(c.database().required());
        assertTrue(c.database().healthCheckOnStartup());
    }

    @Test
    void invalidProviderFallsBackToHexcore() throws Exception {
        PluginConfig c = load("database:\n  provider: \"POSTGRES\"\n");
        assertEquals("HEXCORE", c.database().provider(), "nieznany provider -> HEXCORE");
    }

    @Test
    void databaseRequiredFalseRespected() throws Exception {
        PluginConfig c = load("database:\n  required: false\n  health-check-on-startup: false\n");
        assertFalse(c.database().required());
        assertFalse(c.database().healthCheckOnStartup());
    }

    // ---- #6 item-slots ----

    @Test
    void itemSlotsDefaultGrid() throws Exception {
        PluginConfig c = load("");
        assertEquals(List.of(10, 12, 14, 16, 19, 21, 23, 25, 28, 30, 32, 34), c.auction().itemSlots());
    }

    @Test
    void customItemSlotsPreserveOrder() throws Exception {
        PluginConfig c = load("auction:\n  gui:\n    item-slots: [11, 13, 15]\n");
        assertEquals(List.of(11, 13, 15), c.auction().itemSlots());
    }

    @Test
    void collidingItemSlotsFallBackToDefaultGrid() throws Exception {
        // 45 = paged-slot-back (nawigacja) -> kolizja -> domyślna siatka.
        PluginConfig c = load("auction:\n  gui:\n    item-slots: [10, 45]\n");
        assertEquals(List.of(10, 12, 14, 16, 19, 21, 23, 25, 28, 30, 32, 34), c.auction().itemSlots());
    }

    @Test
    void duplicateItemSlotsFallBackToDefaultGrid() throws Exception {
        PluginConfig c = load("auction:\n  gui:\n    item-slots: [10, 10, 12]\n");
        assertEquals(12, c.auction().itemSlots().size(), "duplikat -> domyślna siatka");
    }

    @Test
    void legacyPageSizeKeyIsIgnored() throws Exception {
        // Stary klucz page-size nie psuje startu i nie wpływa na pojemność.
        PluginConfig c = load("auction:\n  gui:\n    page-size: 30\n");
        assertEquals(12, c.auction().itemSlots().size());
    }

    @Test
    void nonPositiveBazaarPricesUseSafePositiveValues() throws Exception {
        PluginConfig c = loadWithBazaarItems("items:\n"
                + "  test:\n"
                + "    material: STONE\n"
                + "    base-price: 0\n"
                + "    min-price: -5\n"
                + "    max-price: 0\n");
        var item = c.bazaar().item("test").orElseThrow();
        assertTrue(item.basePrice().signum() > 0);
        assertTrue(item.minPrice().signum() > 0);
        assertTrue(item.maxPrice().signum() > 0);
        assertTrue(item.maxPrice().compareTo(item.minPrice()) >= 0);
    }

    @Test
    void invertedBazaarRangeIsMadeConsistent() throws Exception {
        PluginConfig c = loadWithBazaarItems("items:\n"
                + "  test:\n"
                + "    material: STONE\n"
                + "    base-price: 100\n"
                + "    min-price: 10\n"
                + "    max-price: 2\n");
        var item = c.bazaar().item("test").orElseThrow();
        assertEquals(item.minPrice(), item.maxPrice());
        assertEquals(item.minPrice(), item.basePrice());
    }
}
