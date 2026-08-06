package hex.auctionbazaar.config;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Punkt #11: walidacja parametrów pricingu Rynku (elasticity/reference-stock/spread/max-step)
 * oraz punkt #7: normalizacja kwot aukcji (skala/granice DECIMAL) i osiągalność przedmiotów.
 */
class ConfigPricingValidationTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    @Test
    void clampsNegativeToMin() {
        assertEquals(BigDecimal.ZERO,
                ConfigLoader.clampPricing(new BigDecimal("-1"), BigDecimal.ZERO, null, "elasticity"));
        assertEquals(BigDecimal.ONE,
                ConfigLoader.clampPricing(new BigDecimal("0"), BigDecimal.ONE, null, "reference-stock"));
    }

    @Test
    void clampsAboveMax() {
        assertEquals(new BigDecimal("100"),
                ConfigLoader.clampPricing(new BigDecimal("250"), BigDecimal.ZERO,
                        new BigDecimal("100"), "spread"));
    }

    @Test
    void keepsValueInRange() {
        BigDecimal v = new BigDecimal("5");
        assertEquals(v, ConfigLoader.clampPricing(v, BigDecimal.ZERO, new BigDecimal("100"), "spread"));
    }

    @Test
    void nullFallsBackToMin() {
        assertEquals(BigDecimal.ONE,
                ConfigLoader.clampPricing(null, BigDecimal.ONE, null, "reference-stock"));
    }

    // ---- #7: normalizacja kwot aukcji ----

    @Test
    void auctionMoneyNormalizesScale() {
        // Skala wymuszona na 2 (HALF_UP) - identyczna z kolumną DECIMAL(19,2).
        assertEquals(new BigDecimal("5.00"),
                ConfigLoader.auctionMoney(new BigDecimal("5"), BigDecimal.ONE, "auction.min-price", LOG));
        assertEquals(new BigDecimal("2.35"),
                ConfigLoader.auctionMoney(new BigDecimal("2.345"), BigDecimal.ONE, "auction.min-price", LOG));
    }

    @Test
    void auctionMoneyRejectsOverflowToFallback() {
        // Poza zakresem DECIMAL(19,2) -> (znormalizowany) fallback, nigdy przepełnienie kolumny.
        assertEquals(new BigDecimal("1000000000.00"),
                ConfigLoader.auctionMoney(new BigDecimal("1E30"),
                        new BigDecimal("1000000000"), "auction.max-price", LOG));
        assertEquals(new BigDecimal("0.00"),
                ConfigLoader.auctionMoney(null, BigDecimal.ZERO, "auction.listing-fee", LOG));
    }

    // ---- #7: osiągalność przedmiotów Bazaru ----

    @Test
    void unknownCategoryItemIsReassignedToFallbackNotOrphaned() {
        Map<String, BazaarConfig.CategoryConfig> cats = new LinkedHashMap<>();
        cats.put("blocks", new BazaarConfig.CategoryConfig("blocks", "Bloki", "STONE"));
        cats.put("ores", new BazaarConfig.CategoryConfig("ores", "Rudy", "IRON_ORE"));

        Map<String, BazaarItemConfig> items = new LinkedHashMap<>();
        items.put("diamond", item("diamond", "ores"));         // kategoria istnieje
        items.put("mystery", item("mystery", "nie-istnieje")); // kategoria nieznana -> sierota bez naprawy

        Map<String, BazaarItemConfig> fixed = ConfigLoader.ensureItemsReachable(items, cats, LOG);

        assertEquals("ores", fixed.get("diamond").category(), "znana kategoria zachowana");
        assertEquals("blocks", fixed.get("mystery").category(),
                "nieznana kategoria przypisana do pierwszej (domyślnej), by przedmiot był dostępny");
        // Pozostałe pola przedmiotu nienaruszone.
        assertEquals(Material.DIAMOND, fixed.get("mystery").material());
    }

    @Test
    void emptyCategoriesLeavesItemsUnchanged() {
        Map<String, BazaarItemConfig> items = new LinkedHashMap<>();
        items.put("diamond", item("diamond", "ores"));
        Map<String, BazaarItemConfig> out =
                ConfigLoader.ensureItemsReachable(items, Map.of(), LOG);
        assertNotNull(out.get("diamond"));
        assertEquals("ores", out.get("diamond").category(), "bez kategorii nic nie zmieniamy");
    }

    private static BazaarItemConfig item(String key, String category) {
        return new BazaarItemConfig(key, Material.DIAMOND, "Diament", category,
                new BigDecimal("10"), new BigDecimal("1"), new BigDecimal("1000"), 1000L, true, true);
    }
}
