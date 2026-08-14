package hex.auctionbazaar.gui;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #9B: tymczasową tabliczkę przywracamy WYŁĄCZNIE gdy to NASZA tabliczka danej sesji
 * (OAK_SIGN + dokładnie pasujący marker sesji). Obca/pluginowa OAK_SIGN w tym samym miejscu
 * (brak markera lub inny marker) oraz zmieniony blok NIGDY nie są nadpisywane.
 */
class SignRestoreDecisionTest {

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
    void restoresOnlyOwnSessionSign() {
        String session = "sess-123";
        assertTrue(BukkitSignPromptTransport.shouldRestore(Material.OAK_SIGN, session, session),
                "nasza tabliczka tej sesji -> przywracamy");
    }

    @Test
    void promptMarkerKeyIsStableForProtectionPlugins() {
        assertEquals("hexauctionbazaar", BukkitSignPromptTransport.sessionKey().getNamespace());
        assertEquals("sign_prompt_session", BukkitSignPromptTransport.sessionKey().getKey());
    }

    @Test
    void foreignOakSignAtSameLocationIsNotOverwritten() {
        String session = "sess-123";
        // Obca OAK_SIGN bez naszego markera - NIE nadpisujemy.
        assertFalse(BukkitSignPromptTransport.shouldRestore(Material.OAK_SIGN, null, session),
                "obca OAK_SIGN bez markera");
        // OAK_SIGN z markerem INNEJ sesji - też nie nasza.
        assertFalse(BukkitSignPromptTransport.shouldRestore(Material.OAK_SIGN, "inna-sesja", session),
                "OAK_SIGN innej sesji");
    }

    @Test
    void changedBlockOrMissingSessionIsNotRestored() {
        String session = "sess-123";
        assertFalse(BukkitSignPromptTransport.shouldRestore(Material.STONE, session, session),
                "blok już zmieniony (nie tabliczka)");
        assertFalse(BukkitSignPromptTransport.shouldRestore(Material.OAK_SIGN, session, null),
                "brak oczekiwanej sesji -> nic nie przywracamy");
    }
}
