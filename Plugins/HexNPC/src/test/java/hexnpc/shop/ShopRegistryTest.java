package hexnpc.shop;

import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopRegistryTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void loadsShopItemsPricesAndSlots() throws Exception {
        String yaml = """
                shops:
                  starter:
                    title: "&8Sklep startowy"
                    size: 54
                    sell-slot: 49
                    items:
                      cobblestone:
                        material: COBBLESTONE
                        amount: 64
                        slot: 10
                        display-name: "&7Bruk"
                        lore:
                          - "&8Podstawowy blok"
                        buy-price: "100.00"
                        sell-price: "25.00"
                        buy-enabled: true
                        sell-enabled: true
                        sell-match: PLAIN_MATERIAL
                      diamond:
                        material: DIAMOND
                        amount: 1
                        slot: 12
                        display-name: "&bDiament"
                        buy-price: "500.00"
                        sell-price: "200.00"
                """;
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        int count = registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults());
        assertEquals(1, count);
        Shop shop = registry.find("starter").orElseThrow();
        assertEquals(54, shop.size());
        assertEquals(49, shop.sellSlot());
        assertEquals(2, shop.itemValues().size());

        ShopItem stone = shop.item("cobblestone").orElseThrow();
        assertEquals(Material.COBBLESTONE, stone.material());
        assertEquals(64, stone.amount());
        assertEquals(10, stone.slot());
        assertEquals(new BigDecimal("100.00").stripTrailingZeros(), stone.buyPrice());
        assertEquals(SellMatch.PLAIN_MATERIAL, stone.sellMatch());

        ShopItem diamond = shop.item("diamond").orElseThrow();
        assertEquals(Material.DIAMOND, diamond.material());
        // sell-match omitted -> defaults to PLAIN_MATERIAL
        assertEquals(SellMatch.PLAIN_MATERIAL, diamond.sellMatch());
    }

    @Test
    void unknownMaterialIsSkippedNotFatal() throws Exception {
        String yaml = """
                shops:
                  test:
                    size: 27
                    sell-slot: 22
                    items:
                      bogus:
                        material: NOT_A_REAL_MATERIAL_XYZ
                        amount: 1
                        slot: 4
                        buy-price: "10"
                      stone:
                        material: STONE
                        amount: 1
                        slot: 5
                        buy-price: "10"
                """;
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        int count = registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults());
        assertEquals(1, count);
        Shop shop = registry.find("test").orElseThrow();
        assertNotNull(shop.item("stone").orElse(null));
        assertTrue(shop.item("bogus").isEmpty(),
                "invalid material should be skipped, not fail load");
    }

    @Test
    void emptyShopsSectionYieldsEmptyRegistry() throws Exception {
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        int count = registry.reloadFrom(new StringReader("shops: {}\n"), ShopConfig.defaults());
        assertEquals(0, count);
    }
}
