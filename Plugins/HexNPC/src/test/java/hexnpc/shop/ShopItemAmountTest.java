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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprawdza, że ShopItem ogranicza amount do 1..material.getMaxStackSize()
 * (Wariant B — brak transakcji wielo-stosowych w v1). Konstruktor rzuca
 * dla nieprawidłowych wartości, a loader shops.yml pomija takie itemy
 * z wpisem w logu.
 */
class ShopItemAmountTest {

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
    void amountAtMaxStackIsAccepted() {
        // DIAMOND ma maxStackSize=64.
        ShopItem item = new ShopItem(
                "diamond", Material.DIAMOND, 64, 0,
                "", List.of(),
                BigDecimal.ZERO, new BigDecimal("1"),
                false, true, SellMatch.PLAIN_MATERIAL
        );
        assertNotNull(item);
        assertEquals(64, item.amount());
    }

    @Test
    void amountAboveMaxStackIsRejected() {
        // 65 > maxStackSize(64) → IAE.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ShopItem(
                        "diamond", Material.DIAMOND, 65, 0,
                        "", List.of(),
                        BigDecimal.ZERO, new BigDecimal("1"),
                        false, true, SellMatch.PLAIN_MATERIAL));
        assertTrue(ex.getMessage().toLowerCase().contains("amount"),
                "komunikat powinien wskazywać amount; było: " + ex.getMessage());
    }

    @Test
    void amountAboveMaxForNonStackableMaterialIsRejected() {
        // DIAMOND_SWORD ma maxStackSize=1. amount=2 → IAE.
        assertThrows(IllegalArgumentException.class,
                () -> new ShopItem(
                        "sword", Material.DIAMOND_SWORD, 2, 0,
                        "", List.of(),
                        BigDecimal.ZERO, new BigDecimal("1"),
                        false, true, SellMatch.PLAIN_MATERIAL));
    }

    @Test
    void loaderSkipsItemWithAmountAboveMaxStack() throws Exception {
        // Loader powinien obsłużyć IAE z konstruktora ShopItem i pominąć
        // wadliwy item, ale wczytać sąsiednie poprawne wpisy.
        String yaml = """
                shops:
                  mixed:
                    size: 27
                    sell-slot: 22
                    items:
                      too-much:
                        material: DIAMOND
                        amount: 999
                        slot: 0
                        buy-price: "1"
                      ok-stone:
                        material: STONE
                        amount: 64
                        slot: 1
                        buy-price: "1"
                """;
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        int loaded = registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults());
        assertEquals(1, loaded);
        Shop shop = registry.find("mixed").orElseThrow();
        assertTrue(shop.item("too-much").isEmpty(),
                "item z amount poza zakresem musi zostać pominięty");
        assertTrue(shop.item("ok-stone").isPresent(),
                "sąsiedni poprawny item dalej musi się załadować");
    }
}
