package hex.auctionbazaar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domyslne opcje ilosci w GUI Bazaru:
 *  - 1 = pojedyncza sztuka
 *  - 64 = jeden stack
 *  - 576 = 9 stackow (jeden rzad ekwipunku)
 *  - "wlasna ilosc" (przez znak) obslugujemy oddzielnie w BazaarItemGui
 * Testy sprawdzaja domyslne wartosci wystawione w BazaarConfig i
 * kolejnosc, w jakiej beda widoczne w GUI.
 */
class BazaarQuantityOptionsTest {

    private static final List<Long> DEFAULTS = List.of(1L, 64L, 576L);

    @Test
    void defaultOptionsAreOneStackAndRow() {
        assertTrue(DEFAULTS.contains(1L));
        assertTrue(DEFAULTS.contains(64L));
        assertTrue(DEFAULTS.contains(576L),
                "domyslny preset musi zawierac 576 (9 stackow = rzad ekwipunku)");
        assertEquals(3, DEFAULTS.size(),
                "domyslnie 3 presety + custom przez znak (4 przyciski razem)");
    }

    @Test
    void defaultsAreStrictlyIncreasing() {
        for (int i = 1; i < DEFAULTS.size(); i++) {
            assertTrue(DEFAULTS.get(i) > DEFAULTS.get(i - 1),
                    "presets musza byc rosnace: " + DEFAULTS);
        }
    }
}
