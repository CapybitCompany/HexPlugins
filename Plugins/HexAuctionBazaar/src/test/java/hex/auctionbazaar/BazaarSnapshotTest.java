package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.model.BazaarPrice;
import hex.auctionbazaar.bazaar.service.BazaarService;
import hex.auctionbazaar.config.BazaarItemConfig;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BazaarSnapshotTest {

    @Test
    void snapshotSpreadIsBuyMinusSell() {
        BazaarItemConfig item = new BazaarItemConfig(
                "diamond", Material.DIAMOND, "Diamond", "minerals",
                new BigDecimal("500"), new BigDecimal("50"), new BigDecimal("5000"),
                1000L, true, true);
        BazaarPrice price = new BazaarPrice(
                new BigDecimal("500.00"),
                new BigDecimal("512.50"),
                new BigDecimal("487.50"));
        BazaarService.Snapshot snap = new BazaarService.Snapshot(item, price, 1000L);
        assertEquals(new BigDecimal("25.00"), snap.spread());
        assertEquals(1000L, snap.stock());
        // Lore w GUI musi uzywac ceny buy/sell z snapshotu (a NIE base ceny).
        assertEquals(new BigDecimal("512.50"), snap.price().buyPrice());
        assertEquals(new BigDecimal("487.50"), snap.price().sellPrice());
    }
}
