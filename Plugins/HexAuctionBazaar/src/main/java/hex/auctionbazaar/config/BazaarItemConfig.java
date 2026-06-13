package hex.auctionbazaar.config;

import org.bukkit.Material;

import java.math.BigDecimal;

public record BazaarItemConfig(
        String key,
        Material material,
        String displayName,
        String category,
        BigDecimal basePrice,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        long initialStock,
        boolean buyEnabled,
        boolean sellEnabled
) {
}
