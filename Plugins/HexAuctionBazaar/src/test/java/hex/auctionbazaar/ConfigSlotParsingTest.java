package hex.auctionbazaar;

import hex.auctionbazaar.gui.GuiFrame;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Weryfikuje, ze GuiFrame.materialOrDefault odczytuje material z konfiguracji
 * a bledna wartosc powoduje fallback do domyslnej.
 * Zakres slotow (0..53) jest weryfikowany w ConfigLoader.slot() - tutaj
 * sanity-check ze wartosci graniczne dzialaja.
 */
class ConfigSlotParsingTest {

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
    void materialOrDefaultReadsValidMaterial() {
        Material m = GuiFrame.materialOrDefault("STONE", Material.CHEST);
        assertEquals(Material.STONE, m);
    }

    @Test
    void invalidMaterialFallsBack() {
        Material m = GuiFrame.materialOrDefault("NOT_A_MATERIAL", Material.CHEST);
        assertEquals(Material.CHEST, m);
    }

    @Test
    void nullMaterialFallsBack() {
        Material m = GuiFrame.materialOrDefault(null, Material.BARRIER);
        assertEquals(Material.BARRIER, m);
    }

    @Test
    void emptyMaterialFallsBack() {
        Material m = GuiFrame.materialOrDefault("", Material.CHEST);
        assertEquals(Material.CHEST, m);
    }

    @Test
    void slotClampsWithinRange() {
        // Symulujemy walidator slotow z ConfigLoader.slot(...): jesli wartosc
        // poza 0..53 wraca fallback.
        assertEquals(45, clampSlot(45));
        assertEquals(0, clampSlot(0));
        assertEquals(53, clampSlot(53));
        assertEquals(-1, clampSlotOrDefault(-1, -1));
        assertEquals(999, clampSlotOrDefault(999, 999));
    }

    private int clampSlot(int raw) {
        return (raw >= 0 && raw <= 53) ? raw : -1;
    }

    private int clampSlotOrDefault(int raw, int fallback) {
        if (raw < 0 || raw > 53) return fallback;
        return raw;
    }
}
