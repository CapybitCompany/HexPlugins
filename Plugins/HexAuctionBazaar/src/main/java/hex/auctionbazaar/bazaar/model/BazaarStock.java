package hex.auctionbazaar.bazaar.model;

import java.math.BigDecimal;

public record BazaarStock(
        String itemKey,
        long stock,
        BigDecimal lastBuyPrice,
        BigDecimal lastSellPrice,
        long updatedAt
) {
}
