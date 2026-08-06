package hex.auctionbazaar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprawdza, ze messages.yml nie zawiera jawnie widocznych anglojezycznych
 * etykiet ktore powinny byc po polsku - w tym w outputcie audit-log
 * (market=, actor=, item=, amount=, total=, order=, listing=) oraz w
 * lore aukcji (Seller:, Price:).
 * Wykrywa regresy jesli ktos znowu wprowadzi angielskie stringi.
 */
class MessageResourcesPolishTest {

    private static final List<String> FORBIDDEN_ENGLISH_LABELS = List.of(
            "market=", "actor=", "item=", "amount=", "total=",
            "order=", "listing=",
            "Seller:", "Price:", "Listing #",
            "Auction House", "My listings", "Choose amount",
            "Buy 1", "Sell 1", "&aBuy ", "&cSell ",
            "Status:", "Reason:",
            "Right-click to cancel", "Left-click to collect",
            "Buy for", "Cancel &r"
    );

    @Test
    void messagesYmlHasNoLeftoverEnglishLabels() throws IOException {
        Path p = Path.of("src/main/resources/messages.yml");
        String text = Files.readString(p, StandardCharsets.UTF_8);
        for (String f : FORBIDDEN_ENGLISH_LABELS) {
            assertFalse(text.contains(f),
                    "messages.yml zawiera zabroniony anglojezyczny fragment: " + f);
        }
    }

    @Test
    void messagesYmlHasAuditFieldKeysInPolish() throws IOException {
        Path p = Path.of("src/main/resources/messages.yml");
        String text = Files.readString(p, StandardCharsets.UTF_8);
        assertTrue(text.contains("admin-audit-field-market"), "musi zawierac audit-market key");
        assertTrue(text.contains("rynek="), "audit label powinien byc 'rynek='");
        assertTrue(text.contains("gracz="), "audit label powinien byc 'gracz='");
        assertTrue(text.contains("przedmiot="), "audit label powinien byc 'przedmiot='");
        assertTrue(text.contains("ilość="), "audit label powinien byc 'ilość='");
        assertTrue(text.contains("suma="), "audit label powinien byc 'suma='");
    }

    @Test
    void messagesYmlUsesProperPolishDiacritics() throws IOException {
        Path p = Path.of("src/main/resources/messages.yml");
        String text = Files.readString(p, StandardCharsets.UTF_8);
        // Kluczowe polskie znaki, ktore musza byc obecne w wypisach dla gracza.
        assertTrue(text.contains("Odbiór"), "brak diacritic w 'Odbiór'");
        assertTrue(text.contains("Ilość") || text.contains("ilość"),
                "brak diacritic w 'Ilość'");
        assertTrue(text.contains("Sprzedaż") || text.contains("sprzedaż"),
                "brak diacritic w 'Sprzedaż'");
        assertTrue(text.contains("Odśwież") || text.contains("odśwież"),
                "brak diacritic w 'Odśwież'");
        assertTrue(text.contains("Następna") || text.contains("następna"),
                "brak diacritic w 'Następna'");
        assertTrue(text.contains("Przedmiot"), "'Przedmiot' obecny");
        assertTrue(text.contains("uprawnień"), "brak diacritic w 'uprawnień'");
    }

    @Test
    void messagesYmlDoesNotUseAsciiForCriticalPolishWords() throws IOException {
        Path p = Path.of("src/main/resources/messages.yml");
        String text = Files.readString(p, StandardCharsets.UTF_8);
        // ASCII wersje slow, ktore MUSZA byc z diacritics.
        assertFalse(text.contains("Odbior "), "'Odbior' powinien byc 'Odbiór'");
        assertFalse(text.contains("nagrod"), "'nagrod' -> nie powinno wystapic; claims to 'Odbiór przedmiotów'");
    }

    @Test
    void messagesYmlHasFriendlyClaimReasons() throws IOException {
        Path p = Path.of("src/main/resources/messages.yml");
        String text = Files.readString(p, StandardCharsets.UTF_8);
        assertTrue(text.contains("claim-reasons"),
                "musi zawierac sekcje mapowan przyjaznych powodow claim-ow");
        assertTrue(text.contains("Zakup z Rynku"),
                "przyjazny tekst dla bazaar-buy-overflow");
        assertTrue(text.contains("Wypłata"),
                "przyjazny tekst z 'Wypłata' (diacritic)");
    }

    @Test
    void messagesYmlHasListingLoreKeys() throws IOException {
        Path p = Path.of("src/main/resources/messages.yml");
        String text = Files.readString(p, StandardCharsets.UTF_8);
        assertTrue(text.contains("listing-seller"), "musi zawierac listing-seller key");
        assertTrue(text.contains("listing-price"), "musi zawierac listing-price key");
        assertTrue(text.contains("Sprzedawca:"), "polski label Sprzedawca:");
    }

    @Test
    void configYmlHasOrderExpiryKeys() throws IOException {
        Path p = Path.of("src/main/resources/config.yml");
        String text = Files.readString(p, StandardCharsets.UTF_8);
        assertTrue(text.contains("order-expiry-seconds"),
                "config.yml musi udostepniac klucz order-expiry-seconds");
        assertTrue(text.contains("order-expiry-scan-interval-ticks"),
                "config.yml musi udostepniac klucz order-expiry-scan-interval-ticks");
    }

    @Test
    void messagesYmlHasMigratedAuctionGuiKeys() throws IOException {
        // Regresja bledu #5: te trzy GUI mialy hardcodowane teksty.
        Path p = Path.of("src/main/resources/messages.yml");
        String text = Files.readString(p, StandardCharsets.UTF_8);
        assertTrue(text.contains("mine-id"), "musi zawierac mine-id key (MyListingsGui)");
        assertTrue(text.contains("mine-status"), "musi zawierac mine-status key");
        assertTrue(text.contains("mine-cancel-hint"), "musi zawierac mine-cancel-hint key");
        assertTrue(text.contains("confirm-buy-button"), "musi zawierac confirm-buy-button (ConfirmGui)");
        assertTrue(text.contains("confirm-cancel-button"), "musi zawierac confirm-cancel-button");
        assertTrue(text.contains("claim-reason"), "musi zawierac claim-reason (ClaimsGui)");
        assertTrue(text.contains("claim-collect-hint"), "musi zawierac claim-collect-hint");
    }

    @Test
    void messagesYmlHasSellPayoutKeys() throws IOException {
        // Regresja bledu #4: nowe stany SELL musza miec konfigurowalne polskie komunikaty.
        Path p = Path.of("src/main/resources/messages.yml");
        String text = Files.readString(p, StandardCharsets.UTF_8);
        assertTrue(text.contains("sell-pending-claim"),
                "brak klucza sell-pending-claim - stan OK_PENDING_CLAIM nie ma wiadomosci");
        assertTrue(text.contains("sell-payout-failed"),
                "brak klucza sell-payout-failed - stan PAYOUT_FAILED nie ma wiadomosci");
    }
}
