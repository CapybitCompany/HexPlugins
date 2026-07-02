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

    private static int offset(int page) {
        return Math.max(0, page) * PAGE_SIZE;
    }

    private static int totalPages(int total) {
        return Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
    }
}
