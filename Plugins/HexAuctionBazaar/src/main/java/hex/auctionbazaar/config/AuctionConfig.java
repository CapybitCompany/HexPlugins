package hex.auctionbazaar.config;

import java.math.BigDecimal;
import java.util.List;

/**
 * Konfiguracja Domu Aukcyjnego.
 * Uwzględnia również sloty i material ramki dla głównego GUI, aby operator
 * mógł je nadpisać bez zmiany kodu.
 */
public record AuctionConfig(
        boolean enabled,
        long defaultDurationSeconds,
        int maxActiveListingsPerPlayer,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal listingFee,
        BigDecimal saleFeePercent,
        long reservationTtlSeconds,
        int expiryScanIntervalTicks,
        List<BigDecimal> sellPricePresets,
        String guiTitle,
        int pageSize,
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
