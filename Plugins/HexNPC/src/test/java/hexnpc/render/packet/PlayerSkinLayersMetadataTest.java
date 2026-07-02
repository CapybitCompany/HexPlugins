package hexnpc.render.packet;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresja dla CRASHA klientow 1.21.11: stary kod wysylal BYTE pod indeks 17,
 * ktory od 1.21.9 (wprowadzenie klasy Avatar miedzy LivingEntity a Player) jest
 * polem Float (Additional Hearts). Konsekwencja: natychmiastowy disconnect klienta
 * z bledem {@code Invalid entity data item type for field 17 ... new=127(Byte)}.
 */
class PlayerSkinLayersMetadataTest {

    @Test
    void mc1_21_11_resolvesToAvatarIndex16NotPlayerIndex17() {
        Optional<Integer> index = PlayerSkinLayersMetadata.resolve("1.21.11");
        assertEquals(Optional.of(16), index,
                "1.21.11 musi miec skin-parts pod indeksem 16 (Avatar), nie 17 (= Float Additional Hearts)");
    }

    @Test
    void mc1_21_9_and_1_21_10_alsoUseIndex16() {
        // Avatar zostal wstawiony w 1.21.9 (snapshot 25w36a) - od tego momentu indeks = 16.
        assertEquals(Optional.of(16), PlayerSkinLayersMetadata.resolve("1.21.9"));
        assertEquals(Optional.of(16), PlayerSkinLayersMetadata.resolve("1.21.10"));
    }

    @Test
    void mc1_21_0_through_1_21_8_useLegacyIndex17() {
        // Przed 1.21.9 klasa Player miala skin-parts bezposrednio na 17.
        for (int patch = 0; patch <= 8; patch++) {
            String version = "1.21." + patch;
            assertEquals(Optional.of(17), PlayerSkinLayersMetadata.resolve(version),
                    version + " powinno zwrocic 17 (stary layout)");
        }
        assertEquals(Optional.of(17), PlayerSkinLayersMetadata.resolve("1.21"));
    }

    @Test
    void mc1_20_x_usesIndex17() {
        assertEquals(Optional.of(17), PlayerSkinLayersMetadata.resolve("1.20"));
        assertEquals(Optional.of(17), PlayerSkinLayersMetadata.resolve("1.20.6"));
    }

    @Test
    void unknownFutureVersionReturnsEmpty() {
        // 1.22+ - nieznany layout, lepiej brakujaca peleryna niz wykopany klient.
        assertTrue(PlayerSkinLayersMetadata.resolve("1.22").isEmpty());
        assertTrue(PlayerSkinLayersMetadata.resolve("1.22.3").isEmpty());
        assertTrue(PlayerSkinLayersMetadata.resolve("2.0.0").isEmpty());
    }

    @Test
    void unknownPastFutureMinorReturnsEmpty() {
        // 1.21.12 nie istnieje w naszej sprawdzonej mapie - zwracamy empty, nie zgadujemy.
        assertTrue(PlayerSkinLayersMetadata.resolve("1.21.12").isEmpty());
    }

    @Test
    void preMc1_20_returnsEmpty() {
        // Starsze layouty zmienialy sie wielokrotnie - nie wysylamy.
        assertTrue(PlayerSkinLayersMetadata.resolve("1.19.4").isEmpty());
        assertTrue(PlayerSkinLayersMetadata.resolve("1.8.8").isEmpty());
    }

    @Test
    void malformedOrNullReturnsEmpty() {
        assertTrue(PlayerSkinLayersMetadata.resolve(null).isEmpty());
        assertTrue(PlayerSkinLayersMetadata.resolve("").isEmpty());
        assertTrue(PlayerSkinLayersMetadata.resolve("not-a-version").isEmpty());
        assertTrue(PlayerSkinLayersMetadata.resolve("???").isEmpty());
    }

    @Test
    void allLayersMaskIsZero7F() {
        // Maska "wszystkie warstwy ON" jest taka sama we wszystkich wersjach
        // (bity: cape, jacket, sleeves L/R, pants L/R, hat) - tylko indeks sie zmienia.
        assertEquals((byte) 0x7F, PlayerSkinLayersMetadata.ALL_LAYERS_MASK);
    }
}
