package hex.auctionbazaar.config;

import java.math.BigDecimal;

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
        String guiTitle,
        int pageSize,
        String myListingsTitle,
        String claimsTitle,
        String confirmTitle,
        String permOpen,
        String permSell,
        String permCancelOwn,
        String permAdmin
) {
    public boolean priceInRange(BigDecimal price) {
        return price != null
                && price.compareTo(minPrice) >= 0
                && price.compareTo(maxPrice) <= 0;
    }
}
