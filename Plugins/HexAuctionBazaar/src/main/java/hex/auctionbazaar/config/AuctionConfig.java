package hex.auctionbazaar.config;

import java.math.BigDecimal;
import java.util.List;

/**
 * Konfiguracja Domu Aukcyjnego.
 * Uwzględnia również sloty i material ramki dla głównego GUI, aby operator
 * mógł je nadpisać bez zmiany kodu.
 *
 * Limit aktywnych aukcji rozwiązywany jest przez progi permisji
 * ({@link ListingLimitTier}); {@code maxActiveListingsPerPlayer} pozostaje jako
 * fallback dla starych konfiguracji bez sekcji {@code listing-limits}.
 *
 * Podatek od wystawienia ({@code saleFeePercent} + {@link SaleFeeTier}) jest
 * pobierany z góry przy tworzeniu aukcji.
 */
public record AuctionConfig(
        boolean enabled,
        long defaultDurationSeconds,
        int maxActiveListingsPerPlayer,
        int listingLimitDefault,
        List<ListingLimitTier> listingLimitTiers,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal listingFee,
        BigDecimal saleFeePercent,
        List<SaleFeeTier> saleFeeTiers,
        long reservationTtlSeconds,
        int expiryScanIntervalTicks,
        String guiTitle,
        // Wspólna, konfigurowalna powierzchnia przedmiotów (Przeglądaj / Moje aukcje / Odbiór).
        // Pojemność strony = liczba slotów. Zwalidowana: 0..53, bez duplikatów/kolizji.
        List<Integer> itemSlots,
        String myListingsTitle,
        String claimsTitle,
        String confirmTitle,
        String sellTitle,
        // Dostosowanie wygladu GUI Domu Aukcyjnego
        String frameMaterial,
        int slotPrevPage,
        int slotNextPage,
        int slotRefresh,
        int slotMyListings,
        int slotClaims,
        int slotSellHelp,
        int slotSort,
        int slotEmptyState,
        // Sloty nawigacji dla widoków stronicowanych (Odbiór / Moje aukcje).
        int pagedSlotBack,
        int pagedSlotPrevPage,
        int pagedSlotNextPage,
        int pagedSlotPageInfo,
        String permOpen,
        String permSell,
        String permCancelOwn,
        String permAdmin,
        String permAdminAudit
) {
    public boolean priceInRange(BigDecimal price) {
        return price != null
                && price.compareTo(minPrice) >= 0
                && price.compareTo(maxPrice) <= 0;
    }
}
