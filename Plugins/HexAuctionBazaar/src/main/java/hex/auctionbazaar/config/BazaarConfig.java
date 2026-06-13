package hex.auctionbazaar.config;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public record BazaarConfig(
        boolean enabled,
        boolean requirePlainItem,
        Pricing pricing,
        String guiTitle,
        String itemGuiTitle,
        String quantityGuiTitle,
        String permOpen,
        String permBuy,
        String permSell,
        String permAdmin,
        Map<String, BazaarItemConfig> items
) {
    public Optional<BazaarItemConfig> item(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(items.get(key.toLowerCase()));
    }

    public record Pricing(
            BigDecimal elasticity,
            BigDecimal referenceStock,
            BigDecimal buySellSpreadPercent,
            BigDecimal maxStepPerTransactionPercent
    ) {
    }
}
