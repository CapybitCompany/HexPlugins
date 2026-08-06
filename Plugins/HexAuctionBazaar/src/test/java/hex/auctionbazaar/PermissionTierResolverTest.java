package hex.auctionbazaar;

import hex.auctionbazaar.config.ListingLimitTier;
import hex.auctionbazaar.config.SaleFeeTier;
import hex.auctionbazaar.util.ListingLimitResolver;
import hex.auctionbazaar.util.SaleFeeResolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Punkt #8 (limit aukcji) i #9 (podatek) - rozwiązywanie przez progi permisji
 * LuckPerks. Limit: najwyższy pasujący. Podatek: najniższy pasujący.
 * Kolejność progów w liście nie może wpływać na wynik.
 */
class PermissionTierResolverTest {

    private static Predicate<String> perms(String... granted) {
        Set<String> set = Set.of(granted);
        return set::contains;
    }

    private static final List<ListingLimitTier> LIMIT_TIERS = List.of(
            new ListingLimitTier("vip", "hexauction.limit.vip", 20),
            new ListingLimitTier("premium", "hexauction.limit.premium", 30));

    private static final List<SaleFeeTier> FEE_TIERS = List.of(
            new SaleFeeTier("vip", "hexauction.tax.vip", new BigDecimal("8")),
            new SaleFeeTier("premium", "hexauction.tax.premium", new BigDecimal("5")));

    // ---- limit ----

    @Test
    void noMatchUsesDefaultLimit() {
        assertEquals(10, ListingLimitResolver.resolve(perms(), 10, LIMIT_TIERS));
    }

    @Test
    void singleTierWins() {
        assertEquals(20, ListingLimitResolver.resolve(perms("hexauction.limit.vip"), 10, LIMIT_TIERS));
    }

    @Test
    void highestMatchingLimitWins() {
        assertEquals(30, ListingLimitResolver.resolve(
                perms("hexauction.limit.vip", "hexauction.limit.premium"), 10, LIMIT_TIERS));
    }

    @Test
    void limitOrderDoesNotMatter() {
        List<ListingLimitTier> reversed = List.of(
                new ListingLimitTier("premium", "hexauction.limit.premium", 30),
                new ListingLimitTier("vip", "hexauction.limit.vip", 20));
        assertEquals(30, ListingLimitResolver.resolve(
                perms("hexauction.limit.vip", "hexauction.limit.premium"), 10, reversed));
    }

    @Test
    void tierBelowDefaultNeverLowersLimit() {
        List<ListingLimitTier> small = List.of(
                new ListingLimitTier("small", "hexauction.limit.small", 3));
        assertEquals(10, ListingLimitResolver.resolve(perms("hexauction.limit.small"), 10, small));
    }

    // ---- podatek ----

    @Test
    void noMatchUsesDefaultPercent() {
        assertEquals(new BigDecimal("10"),
                SaleFeeResolver.resolve(perms(), new BigDecimal("10"), FEE_TIERS));
    }

    @Test
    void lowestMatchingPercentWins() {
        assertEquals(new BigDecimal("5"), SaleFeeResolver.resolve(
                perms("hexauction.tax.vip", "hexauction.tax.premium"), new BigDecimal("10"), FEE_TIERS));
    }

    @Test
    void singleTaxTierApplies() {
        assertEquals(new BigDecimal("8"), SaleFeeResolver.resolve(
                perms("hexauction.tax.vip"), new BigDecimal("10"), FEE_TIERS));
    }

    @Test
    void taxOrderDoesNotMatter() {
        List<SaleFeeTier> reversed = List.of(
                new SaleFeeTier("premium", "hexauction.tax.premium", new BigDecimal("5")),
                new SaleFeeTier("vip", "hexauction.tax.vip", new BigDecimal("8")));
        assertEquals(new BigDecimal("5"), SaleFeeResolver.resolve(
                perms("hexauction.tax.vip", "hexauction.tax.premium"), new BigDecimal("10"), reversed));
    }

    @Test
    void higherTaxTierNeverRaisesAboveDefault() {
        List<SaleFeeTier> worse = List.of(
                new SaleFeeTier("worse", "hexauction.tax.worse", new BigDecimal("15")));
        assertEquals(new BigDecimal("10"), SaleFeeResolver.resolve(
                perms("hexauction.tax.worse"), new BigDecimal("10"), worse));
    }
}
