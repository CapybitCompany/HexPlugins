package hex.auctionbazaar.auction.gui;

/**
 * Czyste funkcje stronicowania oparte o LICZBĘ prawidłowych slotów przedmiotów
 * (punkt #6). Pojemność strony = liczba item-slotów; count/LIMIT/OFFSET używają
 * tej samej wartości.
 */
final class AuctionItemArea {

    private AuctionItemArea() {
    }

    static int totalPages(int total, int capacity) {
        int cap = Math.max(1, capacity);
        return Math.max(1, (int) Math.ceil(total / (double) cap));
    }

    static int clampPage(int page, int totalPages) {
        int p = Math.max(0, page);
        return p > totalPages - 1 ? totalPages - 1 : p;
    }

    static int offset(int page, int capacity) {
        return Math.max(0, page) * Math.max(1, capacity);
    }
}
