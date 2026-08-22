package hexnpc.shop;

import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.model.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.StringReader;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopLookupTest {

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
    void shopItemLookupIsCaseInsensitive() throws Exception {
        String yaml = """
                shops:
                  Mixed:
                    size: 27
                    sell-slot: 22
                    items:
                      DIAMOND:
                        material: DIAMOND
                        amount: 1
                        slot: 0
                        buy-price: "1"
                """;
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults());

        Shop shop = registry.find("mixed").orElseThrow();
        // YAML key was DIAMOND. Lookups must work in any casing.
        assertTrue(shop.item("DIAMOND").isPresent());
        assertTrue(shop.item("diamond").isPresent());
        assertTrue(shop.item("DiAmOnD").isPresent());
    }

    @Test
    void shopIdLookupIsCaseInsensitive() throws Exception {
        String yaml = """
                shops:
                  STARTER:
                    size: 9
                    sell-slot: 4
                    items:
                      stone:
                        material: STONE
                        amount: 1
                        slot: 0
                        buy-price: "1"
                """;
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults());

        assertTrue(registry.find("starter").isPresent());
        assertTrue(registry.find("STARTER").isPresent());
        assertTrue(registry.find("Starter").isPresent());
    }
}
