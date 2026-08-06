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
 * Punkty #3/#2/#5/#9/#10 + akceptacja "wszystkie nowe teksty po polsku z
 * poprawnymi znakami". Skanuje zasoby i wybrane źródła.
 */
class BrandingAndMessagesTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void configHasRynekBrandingAndPrefix() throws IOException {
        String cfg = read("src/main/resources/config.yml");
        assertTrue(cfg.contains("prefix: \"&0[&4HEX &6RYNEK&0] &r\""), "globalny prefix RYNEK");
        assertTrue(cfg.contains("title: \"&8&lRynek\""), "tytuł Bazaru = Rynek");
        assertFalse(cfg.contains("&8&lBazar"), "brak starego brandingu Bazar w tytule");
    }

    @Test
    void configHasNoSellPricePresets() throws IOException {
        String cfg = read("src/main/resources/config.yml");
        assertFalse(cfg.contains("sell-price-presets"), "presety cen usunięte z domyślnej konfiguracji");
    }

    @Test
    void auctionSellGuiHasNoPricePresets() throws IOException {
        String src = read("src/main/java/hex/auctionbazaar/auction/gui/AuctionSellGui.java");
        // Brak mechanizmu presetów: żadnych PRESET_SLOTS ani odwołań do konfiguracji presetów.
        assertFalse(src.contains("PRESET_SLOTS"), "AuctionSellGui nie ma slotów presetów");
        assertFalse(src.contains("sellPricePresets"), "AuctionSellGui nie czyta presetów z configu");
        String cfg = read("src/main/java/hex/auctionbazaar/config/AuctionConfig.java");
        assertFalse(cfg.contains("sellPricePresets"), "AuctionConfig nie ma pola presetów");
    }

    @Test
    void messagesHaveNoDeadPrefixEntries() throws IOException {
        String msg = read("src/main/resources/messages.yml");
        assertFalse(msg.contains("&8[&6Aukcje&8]"), "martwy prefix auction usunięty");
        assertFalse(msg.contains("&8[&eBazar&8]"), "martwy prefix bazaar usunięty");
    }

    @Test
    void newPolishMessagesExistWithDiacritics() throws IOException {
        String msg = read("src/main/resources/messages.yml");
        List<String> required = List.of(
                // #2 kupno własnej aukcji
                "Nie możesz kupić własnej aukcji.",
                // #5 usuwanie anulowanego zlecenia
                "Zlecenie anulowane.",
                "Kliknij PPM, aby usunąć wpis.",
                "Usunięto wpis anulowanego zlecenia",
                // #9 podsumowanie podatku
                "sell-summary-gross",
                "sell-summary-tax",
                "sell-summary-net",
                "mine-tax",
                "mine-net",
                // #7 przycisk wróć
                "&cWróć",
                // #1 prompt tabliczka/czat
                "Wpisz „anuluj”, aby przerwać.",
                "input-sign-fallback-hint",
                // strony zleceń
                "order-side-buy",
                "order-side-sell");
        for (String key : required) {
            assertTrue(msg.contains(key), "brak wymaganego polskiego tekstu/klucza: " + key);
        }
    }

    @Test
    void listingCreatedShowsGrossTaxNet() throws IOException {
        String msg = read("src/main/resources/messages.yml");
        assertTrue(msg.contains("<gross>") && msg.contains("<tax>") && msg.contains("<net>"),
                "komunikat wystawienia pokazuje brutto/podatek/netto");
    }

    @Test
    void pluginYmlUsesPolishDiacritics() throws IOException {
        String p = read("src/main/resources/plugin.yml");
        for (String ok : List.of("zarządza", "sprzedaż", "własne", "własnych", "Podgląd", "Rynek", "zleceń")) {
            assertTrue(p.contains(ok), "plugin.yml musi zawierać poprawny polski tekst: " + ok);
        }
        for (String bad : List.of("zarzadza", "Podglad", "wlasne", "wlasnych", "sprzedaz ")) {
            assertFalse(p.contains(bad), "plugin.yml nie może zawierać ASCII-polskiego: " + bad);
        }
    }

    @Test
    void newEconomyAndDatabaseMessagesExist() throws IOException {
        String msg = read("src/main/resources/messages.yml");
        for (String key : List.of(
                // #4 rozdzielone komunikaty ekonomii
                "not-enough-money-for-listing", "Wymagane:", "economy-error", "sell-busy", "tax-changed",
                "sell-summary-fee",
                // #1 baza
                "database-unavailable", "dbstatus-provider", "dbstatus-prefix", "NIEDOSTĘPNA",
                "(niedostępny)",
                // Runda 4: rozłączne wyniki kupna/kompensacji
                "bought-claimed", "buy-refunded", "buy-refund-pending", "buy-compensation-failed",
                "compensation-failed", "Odbiór przedmiotów", "administratorem")) {
            assertTrue(msg.contains(key), "brak wymaganego klucza/tekstu: " + key);
        }
    }

    @Test
    void databaseSectionOnlyHasProviderRequiredHealthcheck() throws IOException {
        String cfg = read("src/main/resources/config.yml");
        // Sekcja database nie może zawierać danych połączenia - tylko provider/required/health-check.
        for (String forbidden : List.of("host:", "port:", "username:", "password:", "jdbc")) {
            assertFalse(cfg.contains(forbidden), "database nie może zawierać: " + forbidden);
        }
    }

    @Test
    void configDatabaseSectionDocumentsTablesAndProvider() throws IOException {
        String cfg = read("src/main/resources/config.yml");
        assertTrue(cfg.contains("provider: \"HEXCORE\""), "sekcja database z providerem HEXCORE");
        assertTrue(cfg.contains("hex_auction_listings") && cfg.contains("hex_bazaar_orders"),
                "config dokumentuje używane tabele");
        assertTrue(cfg.contains("item-slots:"), "config zawiera konfigurowalne item-slots");
    }
}
