package hex.auctionbazaar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regresja bledu #5: skanuje pliki zrodlowe GUI i szuka jawnie
 * anglojezycznych stringow ktore powinny byc w messages.yml.
 * Slabsza wersja tego testu wczesniej nie wychwycila hardcodow w
 * AuctionMyListingsGui/AuctionConfirmGui/AuctionClaimsGui.
 */
class GuiSourceEnglishScanTest {

    /**
     * Fragmenty zabronione w plikach zrodlowych .java w katalogu GUI.
     * Sa to jawnie widoczne dla gracza teksty, ktore MUSZA byc konfigurowalne
     * przez messages.yml (a nie hardcodowane w kodzie).
     */
    private static final List<String> FORBIDDEN_LITERALS = List.of(
            "\"&7Listing #\"",
            "\"&7Status: \"",
            "\"&7Price: \"",
            "\"&7Seller: \"",
            "\"&7Reason: \"",
            "\"&cRight-click to cancel\"",
            "\"&aLeft-click to buy\"",
            "\"&aLeft-click to collect\"",
            "\"&aBuy for \"",
            "\"&cCancel\"",
            "Buy for &e",
            "Sell 1",
            "Buy 1"
    );

    @Test
    void guiSourcesDoNotContainForbiddenEnglishLiterals() throws IOException {
        Path guiRoot = Path.of("src/main/java/hex/auctionbazaar");
        try (Stream<Path> stream = Files.walk(guiRoot)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/gui/") || p.toString().contains("\\gui\\"))
                    .forEach(p -> checkFile(p));
        }
    }

    @Test
    void auditServiceHasNoHardcodedFieldLabels() throws IOException {
        Path svc = Path.of("src/main/java/hex/auctionbazaar/audit/service/AuditService.java");
        String text = Files.readString(svc, StandardCharsets.UTF_8);
        // Poprzednio AuditService uzywalo hardcodowanych: "&7market=&f"
        assertFalse(text.contains("\"&7market=\""),
                "AuditService nie moze zawierac hardcoded 'market=' literal");
        assertFalse(text.contains("\"&7actor=\""),
                "AuditService nie moze zawierac hardcoded 'actor=' literal");
        assertFalse(text.contains("\"&7item=\""),
                "AuditService nie moze zawierac hardcoded 'item=' literal");
        assertFalse(text.contains("\"&7amount=\""),
                "AuditService nie moze zawierac hardcoded 'amount=' literal");
        assertFalse(text.contains("\"&7total=\""),
                "AuditService nie moze zawierac hardcoded 'total=' literal");
    }

    private static void checkFile(Path p) {
        try {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            for (String forbidden : FORBIDDEN_LITERALS) {
                if (text.contains(forbidden)) {
                    fail("Plik " + p + " zawiera zabroniony hardcode: " + forbidden);
                }
            }
        } catch (IOException ex) {
            fail("nie mozna przeczytac " + p + ": " + ex.getMessage());
        }
    }
}
