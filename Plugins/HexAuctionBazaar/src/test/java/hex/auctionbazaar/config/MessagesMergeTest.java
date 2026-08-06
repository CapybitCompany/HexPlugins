package hex.auctionbazaar.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #12: stary/niekompletny messages.yml musi nadal rozwiązywać wszystkie klucze -
 * brakujące uzupełniane z gebündelten defaults, a wartości użytkownika NIE są nadpisywane.
 */
class MessagesMergeTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    @Test
    void oldIncompleteFileFallsBackToBundledDefaults(@TempDir Path dir) throws IOException {
        // Bardzo stary plik: tylko jeden nadpisany klucz, reszta brakuje.
        String incomplete = "common:\n  no-permission: \"&cWłasny tekst braku uprawnień\"\n";
        Files.writeString(dir.resolve("messages.yml"), incomplete, StandardCharsets.UTF_8);

        MessagesConfig msg = ConfigLoader.loadMessages(dir.toFile(), LOG);

        // 1) Wartość użytkownika ma pierwszeństwo (nie nadpisana defaultem).
        assertEquals("&cWłasny tekst braku uprawnień", msg.get("common.no-permission"));

        // 2) Brakujące klucze rozwiązują się z defaults - żadnego widocznego "missing message".
        for (String key : new String[]{
                "bazaar.bought", "bazaar.nothing-sold", "common.db-error", "common.economy-error",
                "auction.claim-received", "bazaar.buy-refunded", "common.yes-label", "common.no-label"}) {
            String v = msg.get(key);
            assertTrue(msg.has(key), "klucz uzupełniony z defaults: " + key);
            assertFalse(v.startsWith("&cmissing message"), "brak 'missing message' dla: " + key + " -> " + v);
        }

        // 3) Poprawne polskie diakrityki z defaults.
        assertEquals("Tak", msg.get("common.yes-label"));
    }

    @Test
    void missingFileStillLoadsAllDefaults(@TempDir Path dir) {
        // Brak pliku w ogóle -> loadYaml wypakowuje default; wszystkie klucze dostępne.
        MessagesConfig msg = ConfigLoader.loadMessages(dir.toFile(), LOG);
        assertFalse(msg.get("bazaar.bought").startsWith("&cmissing message"));
        assertFalse(msg.get("common.db-error").startsWith("&cmissing message"));
    }
}
