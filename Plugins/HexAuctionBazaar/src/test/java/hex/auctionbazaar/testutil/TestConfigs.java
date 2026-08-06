package hex.auctionbazaar.testutil;

import hex.auctionbazaar.config.AuctionConfig;
import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.config.BazaarItemConfig;
import org.bukkit.Material;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Fabryki domyślnych configów do testów usług (pełne rekordy z sensownymi wartościami). */
public final class TestConfigs {

    private TestConfigs() {
    }

    /** Domyślny {@link AuctionConfig}; {@code enabled} steruje bramką auction.enabled. */
    public static AuctionConfig auction(boolean enabled) {
        return new AuctionConfig(
                enabled,
                86400L,                 // defaultDurationSeconds
                10,                     // maxActiveListingsPerPlayer (legacy)
                10,                     // listingLimitDefault
                List.of(),              // listingLimitTiers
                new BigDecimal("1"),    // minPrice
                new BigDecimal("1000000"),  // maxPrice
                BigDecimal.ZERO,        // listingFee
                BigDecimal.ZERO,        // saleFeePercent
                List.of(),              // saleFeeTiers
                30L,                    // reservationTtlSeconds
                1200,                   // expiryScanIntervalTicks
                "&8Dom Aukcyjny",       // guiTitle
                List.of(10, 12, 14, 16, 19, 21, 23, 25, 28, 30, 32, 34),   // itemSlots
                "&8Moje aukcje",        // myListingsTitle
                "&8Odbiór",             // claimsTitle
                "&8Potwierdź",          // confirmTitle
                "&8Wystaw",             // sellTitle
                "BLACK_STAINED_GLASS_PANE",   // frameMaterial
                45, 53, 49, 47, 51, 48, 50, 22,   // control slots
                45, 48, 50, 49,         // paged nav slots
                "hexauction.open",
                "hexauction.sell",
                "hexauction.cancel",
                "hexauction.admin",
                "hexauction.admin.audit");
    }

    /** Domyślny {@link BazaarConfig} z jednym przedmiotem "diamond"; {@code enabled} steruje bazaar.enabled. */
    public static BazaarConfig bazaar(boolean enabled) {
        return new BazaarConfig(
                enabled, true, 14, 0L, 6000,
                new BazaarConfig.Pricing(new BigDecimal("0.5"), new BigDecimal("10000"),
                        new BigDecimal("5"), new BigDecimal("5")),
                "&8Rynek", "&8%display%", "&8Q", "&8O", "&8Zlecenie", "GRAY_STAINED_GLASS_PANE",
                List.of(1L, 64L, 576L), false, 20,
                Map.<String, BazaarConfig.CategoryConfig>of(),
                "hexbazaar.open", "hexbazaar.buy", "hexbazaar.sell",
                "hexbazaar.orders", "hexbazaar.order.create.buy",
                "hexbazaar.order.create.sell", "hexbazaar.order.cancel", "hexbazaar.admin",
                Map.of("diamond", new BazaarItemConfig("diamond", Material.DIAMOND, "Diament", "ogólne",
                        new BigDecimal("10"), new BigDecimal("1"), new BigDecimal("1000"),
                        1000L, true, true)));
    }
}
