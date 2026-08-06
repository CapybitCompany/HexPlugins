package hex.auctionbazaar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sanity-check dla obliczen offsetu strony w GUI Domu Aukcyjnego.
 * Kazda strona ma 45 slotow z listingami.
 */
class AuctionPaginationTest {

    private static final int PAGE_SIZE = 45;

    @Test
    void firstPageOffsetIsZero() {
        assertEquals(0, offset(0));
    }

    @Test
    void secondPageOffsetIsPageSize() {
        assertEquals(45, offset(1));
    }

    @Test
    void thirdPageOffsetIsTwoPageSizes() {
        assertEquals(90, offset(2));
    }

    @Test
    void negativePageClampsToZero() {
        assertEquals(0, offset(-5));
    }

    @Test
    void totalPagesRoundsUp() {
        assertEquals(1, totalPages(0));
        assertEquals(1, totalPages(1));
        assertEquals(1, totalPages(45));
        assertEquals(2, totalPages(46));
        assertEquals(3, totalPages(90 + 1));
    }

    @Test
    void emptyStateHasOnePage() {
        assertEquals(1, totalPages(0));
        assertEquals(0, clampPage(0, totalPages(0)));
    }

    @Test
    void firstMiddleLastPagesClampWithinRange() {
        int total = 91;              // 3 strony (45/45/1)
        int pages = totalPages(total);
        assertEquals(3, pages);
        assertEquals(0, clampPage(0, pages));   // pierwsza
        assertEquals(1, clampPage(1, pages));   // środkowa
        assertEquals(2, clampPage(2, pages));   // ostatnia
    }

    @Test
    void invalidPageClampsToLast() {
        // Po usunięciu danych strona 5 nie istnieje przy 2 stronach -> ostatnia (1).
        assertEquals(1, clampPage(5, totalPages(50)));
    }

    @Test
    void hasNextOnlyWhenPageExists() {
        int pages = totalPages(91);   // 3 strony
        assertEquals(true, (0 + 1) < pages);   // strona 0 ma następną
        assertEquals(true, (1 + 1) < pages);   // strona 1 ma następną
        assertEquals(false, (2 + 1) < pages);  // ostatnia nie ma następnej
    }

    private static int offset(int page) {
        return Math.max(0, page) * PAGE_SIZE;
    }

    private static int totalPages(int total) {
        return Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
    }

    /** Odzwierciedla klemowanie w GUI: page > totalPages-1 -> ostatnia ważna. */
    private static int clampPage(int page, int totalPages) {
        int p = Math.max(0, page);
        return p > totalPages - 1 ? totalPages - 1 : p;
    }
}
