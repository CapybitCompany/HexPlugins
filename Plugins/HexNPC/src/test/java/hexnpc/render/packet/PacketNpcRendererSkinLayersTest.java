package hexnpc.render.packet;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprawdza, ze sciezka render PacketNpcRenderera korzysta z
 * {@link PlayerSkinLayersMetadata} zamiast hardkodowac indeks 17, ktory od
 * 1.21.9 jest polem Float (Additional Hearts) i wykopuje klientow.
 */
class PacketNpcRendererSkinLayersTest {

    @Test
    void mc1_21_11_doesNotProduceByteAtIndex17() {
        // Scisla regresja crasha z raportu:
        // "Invalid entity data item type for field 17 ... new=127(Byte)".
        Optional<Integer> index = PacketNpcRenderer.resolveSkinLayersIndex("1.21.11");
        assertTrue(index.isPresent(),
                "Dla 1.21.11 mamy zweryfikowany indeks (16), wiec packet powinien byc wyslany");
        assertNotEquals(17, index.get().intValue(),
                "Nigdy nie wolno wystawic BYTE pod indeksem 17 dla 1.21.11 - klient sie rozlaczy");
        assertEquals(16, index.get().intValue(),
                "Po przeniesieniu do Avatar w 1.21.9 wlasciwy indeks to 16");
    }

    @Test
    void legacy1_21_4_stillUsesIndex17() {
        assertEquals(Optional.of(17), PacketNpcRenderer.resolveSkinLayersIndex("1.21.4"));
    }

    @Test
    void unsupportedVersionProducesNoPacket() {
        // Nieznana wersja: pomijamy metadata, nie wysylamy "zgadywanej" wartosci.
        assertTrue(PacketNpcRenderer.resolveSkinLayersIndex("1.22.0").isEmpty());
        assertTrue(PacketNpcRenderer.resolveSkinLayersIndex("not-a-version").isEmpty());
        assertTrue(PacketNpcRenderer.resolveSkinLayersIndex(null).isEmpty());
    }

    @Test
    void rendererDelegatesToVersionResolver() {
        // Strukturalna gwarancja, ze renderer nie hardkoduje stalej, tylko pyta resolvera.
        for (String version : new String[]{"1.20.6", "1.21", "1.21.5", "1.21.8", "1.21.9", "1.21.11"}) {
            Optional<Integer> expected = PlayerSkinLayersMetadata.resolve(version);
            Optional<Integer> actual = PacketNpcRenderer.resolveSkinLayersIndex(version);
            assertEquals(expected, actual,
                    "Renderer musi uzyc indeksu z resolvera dla " + version);
        }
    }
}
