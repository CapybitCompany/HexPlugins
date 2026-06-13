package hex.auctionbazaar.bazaar.model;

import java.math.BigDecimal;

public record BazaarPrice(
        BigDecimal mid,
        BigDecimal buyPrice,
        BigDecimal sellPrice
) {
}
