package hex.auctionbazaar.gui;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #2: przywrócenie po CZĘŚCIOWEJ awarii prepareSign na KAŻDYM etapie - bez ghost-tabliczki, ale i
 * bez nadpisania cudzej OAK_SIGN. Testujemy czystą decyzję {@link BukkitSignPromptTransport#shouldRestoreAfterPrepareFailure}
 * dla stanów odpowiadających etapom: po setType (przed markerem), przy markerze/liniach oraz przy update.
 */
class SignPromptPrepareRestoreTest {

    private static final String SESSION = "sess-abc";

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void blockNotChangedHasNothingToRestore() {
        // Awaria zanim dotknęliśmy świata (blockChanged=false) -> nic nie przywracamy.
        assertFalse(BukkitSignPromptTransport.shouldRestoreAfterPrepareFailure(
                false, false, Material.AIR, null, SESSION));
    }

    @Test
    void afterSetTypeBeforeMarkerRestoresOurFreshSign() {
        // Etap: błąd bezpośrednio po setType / przed utrwaleniem markera (ten sam synchroniczny tick).
        // W miejscu wciąż stoi NASZA świeża OAK_SIGN (jeszcze bez markera) -> przywracamy (bez ghost-a).
        assertTrue(BukkitSignPromptTransport.shouldRestoreAfterPrepareFailure(
                true, false, Material.OAK_SIGN, null, SESSION));
    }

    @Test
    void beforeMarkerButBlockNoLongerSignIsNotRestored() {
        // Defensywnie: jeśli mimo wszystko w miejscu nie ma OAK_SIGN, nic nie nadpisujemy.
        assertFalse(BukkitSignPromptTransport.shouldRestoreAfterPrepareFailure(
                true, false, Material.STONE, null, SESSION));
    }

    @Test
    void afterMarkerPersistedRestoresOnlyOwnSession() {
        // Etap: błąd przy zapisie linii / kolejnym update, marker JUŻ utrwalony -> restore marker-bezpieczny.
        assertTrue(BukkitSignPromptTransport.shouldRestoreAfterPrepareFailure(
                true, true, Material.OAK_SIGN, SESSION, SESSION), "nasza tabliczka tej sesji");
    }

    @Test
    void afterMarkerForeignSignIsNeverOverwritten() {
        // Obca OAK_SIGN bez markera oraz z markerem INNEJ sesji - NIGDY nie nadpisujemy.
        assertFalse(BukkitSignPromptTransport.shouldRestoreAfterPrepareFailure(
                true, true, Material.OAK_SIGN, null, SESSION), "obca OAK_SIGN bez markera");
        assertFalse(BukkitSignPromptTransport.shouldRestoreAfterPrepareFailure(
                true, true, Material.OAK_SIGN, "inna-sesja", SESSION), "OAK_SIGN innej sesji");
    }

    @Test
    void afterMarkerChangedBlockIsNotRestored() {
        // Marker utrwalony, ale ktoś zmienił blok na inny materiał -> nie nadpisujemy.
        assertFalse(BukkitSignPromptTransport.shouldRestoreAfterPrepareFailure(
                true, true, Material.STONE, SESSION, SESSION));
    }
}
