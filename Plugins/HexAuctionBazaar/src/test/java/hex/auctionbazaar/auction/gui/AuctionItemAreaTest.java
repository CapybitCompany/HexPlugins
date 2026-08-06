package hex.auctionbazaar.auction.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Punkt #6: pojemność strony wynika z LICZBY item-slotów, a count/LIMIT/OFFSET
 * używają tej samej wartości. Testy pierwszej/środkowej/ostatniej/pustej strony.
 */
class AuctionItemAreaTest {

    @Test
    void totalPagesUsesCapacityNotFixed45() {
        // 12 slotów -> strona mieści 12 wpisów.
        assertEquals(1, AuctionItemArea.totalPages(0, 12));
        assertEquals(1, AuctionItemArea.totalPages(12, 12));
        assertEquals(2, AuctionItemArea.totalPages(13, 12));
        assertEquals(3, AuctionItemArea.totalPages(25, 12));
    }

    @Test
    void offsetUsesCapacity() {
        assertEquals(0, AuctionItemArea.offset(0, 12));
        assertEquals(12, AuctionItemArea.offset(1, 12));
        assertEquals(24, AuctionItemArea.offset(2, 12));
        assertEquals(0, AuctionItemArea.offset(-3, 12));
    }

    @Test
    void clampFirstMiddleLastAndInvalid() {
        int pages = AuctionItemArea.totalPages(25, 12); // 3 strony
        assertEquals(0, AuctionItemArea.clampPage(0, pages));  // pierwsza
        assertEquals(1, AuctionItemArea.clampPage(1, pages));  // środkowa
        assertEquals(2, AuctionItemArea.clampPage(2, pages));  // ostatnia
        assertEquals(2, AuctionItemArea.clampPage(9, pages));  // poza -> ostatnia
    }

    @Test
    void emptyStateHasSinglePage() {
        assertEquals(1, AuctionItemArea.totalPages(0, 12));
        assertEquals(0, AuctionItemArea.clampPage(0, 1));
    }

    @Test
    void zeroCapacityIsGuardedAgainstDivideByZero() {
        // Pojemność 0 nie może się zdarzyć (item-slots >= 1), ale bronimy się:
        // capacity jest klemowane do 1, więc brak dzielenia przez zero.
        assertEquals(5, AuctionItemArea.totalPages(5, 0));
        assertEquals(3, AuctionItemArea.offset(3, 0));
    }
}
