package hex.auctionbazaar.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Konfiguracja Bazaru.
 * Zawiera pola sterujące wyglądem GUI (material ramki) oraz ustawieniami
 * zleceń (limit, ttl, interwał skanera wygasania), a także konfiguracje
 * auto-refresh live-market view.
 */
public record BazaarConfig(
        boolean enabled,
        boolean requirePlainItem,
        int maxOrdersPerPlayer,
        long orderExpirySeconds,
        int orderExpiryScanIntervalTicks,
        Pricing pricing,
        String guiTitle,
        String itemGuiTitle,
        String quantityGuiTitle,
        String ordersGuiTitle,
        String orderCreateGuiTitle,
        String frameMaterial,
        List<Long> quantityOptions,
        boolean autoRefreshEnabled,
        int autoRefreshIntervalTicks,
        long snapshotCacheMs,
        Map<String, CategoryConfig> categories,
        String permOpen,
        String permBuy,
        String permSell,
        String permOrders,
        String permOrderBuy,
        String permOrderSell,
        String permOrderCancel,
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

    public record CategoryConfig(
            String key,
            String displayName,
            String material
    ) {
    }
}
