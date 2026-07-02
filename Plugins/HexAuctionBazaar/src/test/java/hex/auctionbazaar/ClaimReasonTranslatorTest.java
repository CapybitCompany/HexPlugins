package hex.auctionbazaar;

import hex.auctionbazaar.util.ClaimReasonTranslator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regresja Part B #5: techniczne kody powodu claim-u sa tlumaczone
 * na przyjazne polskie etykiety w GUI /hexauction claims.
 */
class ClaimReasonTranslatorTest {

    private static java.util.function.Function<String, String> fromMap(Map<String, String> map) {
        return map::get;
    }

    @Test
    void nullOrEmptyReasonReturnsFallback() {
        assertEquals("FALLBACK", ClaimReasonTranslator.friendlyFor(null, k -> null, "FALLBACK"));
        assertEquals("FALLBACK", ClaimReasonTranslator.friendlyFor("", k -> null, "FALLBACK"));
        assertEquals("FALLBACK", ClaimReasonTranslator.friendlyFor("   ", k -> null, "FALLBACK"));
    }

    @Test
    void unknownReasonReturnsFallback() {
        assertEquals("FALLBACK",
                ClaimReasonTranslator.friendlyFor("kompletnie-nieznany-kod",
                        k -> null, "FALLBACK"));
    }

    @Test
    void bazaarBuyOverflowMapsToBazaarLabel() {
        Map<String, String> m = new HashMap<>();
        m.put("claim-reasons.bazaar-buy-overflow", "Zakup z Bazaru: brak miejsca w ekwipunku");
        assertEquals("Zakup z Bazaru: brak miejsca w ekwipunku",
                ClaimReasonTranslator.friendlyFor("bazaar-buy-overflow-diamond",
                        fromMap(m), "FALLBACK"));
    }

    @Test
    void bazaarSellRefundMapsCorrectly() {
        Map<String, String> m = new HashMap<>();
        m.put("claim-reasons.bazaar-sell-refund", "Wypłata ze sprzedaży w Bazarze");
        assertEquals("Wypłata ze sprzedaży w Bazarze",
                ClaimReasonTranslator.friendlyFor("bazaar-sell-refund", fromMap(m), "F"));
    }

    @Test
    void auctionSoldMapsCorrectlyWithIdSuffix() {
        Map<String, String> m = new HashMap<>();
        m.put("claim-reasons.auction-sold", "Wypłata za sprzedaną aukcję");
        assertEquals("Wypłata za sprzedaną aukcję",
                ClaimReasonTranslator.friendlyFor("auction-sold-42", fromMap(m), "F"));
    }

    @Test
    void longerPrefixWinsOverShorter() {
        // "bazaar-buy-refund-claim" bardziej specyficzne niz "bazaar-buy-refund".
        Map<String, String> m = new HashMap<>();
        m.put("claim-reasons.bazaar-buy-refund", "Zwrot za nadpłatę w Bazarze");
        m.put("claim-reasons.bazaar-buy-refund-claim", "Zwrot za nadpłatę (money-claim)");
        assertEquals("Zwrot za nadpłatę (money-claim)",
                ClaimReasonTranslator.friendlyFor("bazaar-buy-refund-claim-diamond",
                        fromMap(m), "F"),
                "dluzszy prefix powinien wygrac");
    }

    @Test
    void exactMatchIsUsedFirst() {
        // Klucz "auction-listing-fee-refund" jest exact match.
        Map<String, String> m = new HashMap<>();
        m.put("claim-reasons.auction-listing-fee-refund", "Zwrot opłaty za wystawienie");
        m.put("claim-reasons.auction-listing-fee", "Opłata za wystawienie (NIE UZYWAJ)");
        assertEquals("Zwrot opłaty za wystawienie",
                ClaimReasonTranslator.friendlyFor("auction-listing-fee-refund",
                        fromMap(m), "F"));
    }

    @Test
    void bazaarOrderFillMapsToFriendly() {
        Map<String, String> m = new HashMap<>();
        m.put("claim-reasons.bazaar-order-fill", "Realizacja zlecenia Bazar");
        assertEquals("Realizacja zlecenia Bazar",
                ClaimReasonTranslator.friendlyFor("bazaar-order-fill-42", fromMap(m), "F"));
    }

    @Test
    void bazaarOrderRefundMapsToFriendly() {
        Map<String, String> m = new HashMap<>();
        m.put("claim-reasons.bazaar-order-refund", "Zwrot za anulowane lub wygasłe zlecenie");
        assertEquals("Zwrot za anulowane lub wygasłe zlecenie",
                ClaimReasonTranslator.friendlyFor("bazaar-order-refund-42", fromMap(m), "F"));
    }
}
