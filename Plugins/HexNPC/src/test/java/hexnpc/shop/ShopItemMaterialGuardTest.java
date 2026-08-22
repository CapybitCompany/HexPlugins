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
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strażnik materiałów: ShopItem przyjmuje tylko realne, obtainable
 * przedmioty. AIR i materiały nie będące itemami (bloki techniczne)
 * muszą zostać odrzucone — zarówno przy bezpośredniej konstrukcji,
 * jak i przez loader shops.yml.
 */
class ShopItemMaterialGuardTest {

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
    void airIsRejectedByConstructor() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ShopItem(
                        "void", Material.AIR, 1, 0,
                        "", List.of(),
                        BigDecimal.ZERO, new BigDecimal("1"),
                        false, true, SellMatch.PLAIN_MATERIAL));
        assertTrue(ex.getMessage().toLowerCase().contains("air"),
                "komunikat błędu musi wskazywać na AIR; było: " + ex.getMessage());
    }

    @Test
    void nonItemBlockMaterialIsRejectedByConstructor() {
        // WATER to materiał technicznego bloku (Material.isItem() == false).
        // Szukamy konkretnego przykładu w bieżącej wersji API — jeśli z
        // jakiegoś powodu WATER stanie się itemem, test po prostu
        // sprawdzi reakcję na material.isItem()==false dla wskazanego
        // przykładu.
        if (Material.WATER.isItem() || Material.WATER.isAir()) {
            // Wybierz inny technical material jako fallback — w 1.21
            // NETHER_PORTAL nie jest itemem.
            assertNonItemRejected(Material.NETHER_PORTAL);
            return;
        }
        assertNonItemRejected(Material.WATER);
    }

    private void assertNonItemRejected(Material material) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ShopItem(
                        "tech", material, 1, 0,
                        "", List.of(),
                        BigDecimal.ZERO, new BigDecimal("1"),
                        false, true, SellMatch.PLAIN_MATERIAL));
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("not an obtainable") || msg.contains("air"),
                "komunikat musi sygnalizować nieprawidłowy materiał; było: " + ex.getMessage());
    }

    @Test
    void loaderSkipsAirAndKeepsNeighbours() throws Exception {
        // AIR pomijany, sąsiednie poprawne pozycje dalej się ładują.
        String yaml = """
                shops:
                  mix:
                    size: 27
                    sell-slot: 22
                    items:
                      air-bad:
                        material: AIR
                        amount: 1
                        slot: 0
                        buy-price: "1"
                      stone-ok:
                        material: STONE
                        amount: 1
                        slot: 1
                        buy-price: "1"
                """;
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        int loaded = registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults());
        assertEquals(1, loaded);
        Shop shop = registry.find("mix").orElseThrow();
        assertTrue(shop.item("air-bad").isEmpty(),
                "AIR musi zostać pominięty przez loader");
        assertTrue(shop.item("stone-ok").isPresent(),
                "sąsiedni STONE musi się dalej załadować");
    }

    @Test
    void loaderSkipsTechnicalBlockMaterialAndKeepsNeighbours() throws Exception {
        // Pesymistycznie: jeśli WATER w danej wersji jest itemem, loader
        // by go zaakceptował — to nie jest błąd testu, tylko zmiana API.
        // Test reaguje wtedy soft-passem (asercja pomijająca pewność).
        if (Material.WATER.isItem() && !Material.WATER.isAir()) {
            return;
        }
        String yaml = """
                shops:
                  mix:
                    size: 27
                    sell-slot: 22
                    items:
                      water-bad:
                        material: WATER
                        amount: 1
                        slot: 0
                        buy-price: "1"
                      stone-ok:
                        material: STONE
                        amount: 1
                        slot: 1
                        buy-price: "1"
                """;
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        int loaded = registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults());
        assertEquals(1, loaded);
        Shop shop = registry.find("mix").orElseThrow();
        assertTrue(shop.item("water-bad").isEmpty(),
                "WATER (nie-item) musi zostać pominięty przez loader");
        assertTrue(shop.item("stone-ok").isPresent(),
                "sąsiedni STONE musi się dalej załadować");
    }

    @Test
    void normalItemMaterialStillAccepted() {
        ShopItem item = new ShopItem(
                "ok", Material.STONE, 1, 0,
                "", List.of(),
                BigDecimal.ZERO, new BigDecimal("1"),
                false, true, SellMatch.PLAIN_MATERIAL
        );
        assertEquals(Material.STONE, item.material());
    }
}
