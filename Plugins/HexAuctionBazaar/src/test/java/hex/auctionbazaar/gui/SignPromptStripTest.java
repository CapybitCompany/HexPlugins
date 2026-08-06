package hex.auctionbazaar.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Punkt #14: tekst na tymczasowej tabliczce nie może pokazywać widocznych legacy-kodów (&a/§a).
 */
class SignPromptStripTest {

    @Test
    void removesLegacyColorAndFormatCodes() {
        assertEquals("Podaj cenę", SignPrompt.stripColors("&aPodaj &lcenę"));
        assertEquals("Podaj cenę", SignPrompt.stripColors("§aPodaj §lcenę"));
        assertEquals("Tekst", SignPrompt.stripColors("&r&fTekst"));
    }

    @Test
    void keepsPlainTextAndHandlesNull() {
        assertEquals("anuluj = koniec", SignPrompt.stripColors("anuluj = koniec"));
        assertEquals("", SignPrompt.stripColors(null));
        // Samotny & bez kodu koloru zostaje (nie jest kodem).
        assertEquals("A & B", SignPrompt.stripColors("A & B"));
    }
}
