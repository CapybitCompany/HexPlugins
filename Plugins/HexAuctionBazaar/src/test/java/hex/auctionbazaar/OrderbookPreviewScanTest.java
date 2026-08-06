package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.model.BazaarOrder;
import hex.auctionbazaar.bazaar.model.OrderSide;
import hex.auctionbazaar.bazaar.model.OrderState;
import hex.auctionbazaar.bazaar.repository.BazaarOrderRepository;
import hex.auctionbazaar.bazaar.service.BazaarOrderService;
import hex.auctionbazaar.bazaar.service.BazaarOrderService.MatchPreview;
import hex.auctionbazaar.testutil.InMemoryDb;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #5: podgląd dopasowania orderbooka NIE może być ograniczony do pierwszych 20 ofert.
 * Skanuje strony aż do pokrycia ilości / limitu ceny / wyczerpania. Testuje czysty seam
 * {@link BazaarOrderService#scanPreview} oraz realny SQL stronicowania (LIMIT ? OFFSET ?).
 */
class OrderbookPreviewScanTest {

    private static BazaarOrder sell(long id, long remaining, String price) {
        return new BazaarOrder(id, UUID.randomUUID(), "s", "diamond", OrderSide.SELL,
                remaining, remaining, new BigDecimal(price), BigDecimal.ZERO,
                OrderState.ACTIVE, 0L, 0L, null);
    }

    /** Stronicujący fetcher po liście (symuluje LIMIT/OFFSET po stronie DB). */
    private static BazaarOrderService.OrderPageFetcher pager(List<BazaarOrder> all, int pageSize) {
        return offset -> {
            if (offset >= all.size()) return List.of();
            return all.subList(offset, Math.min(all.size(), offset + pageSize));
        };
    }

    @Test
    void coversTwentyOneSingleItemOffersBeyondFirstPage() {
        // 21 ofert po 1 szt.; rozmiar strony 20 -> stary cap zwróciłby 20, poprawny skan 21.
        List<BazaarOrder> all = new ArrayList<>();
        for (int i = 1; i <= 21; i++) all.add(sell(i, 1, "5"));
        MatchPreview p = BazaarOrderService.scanPreview(21, 20, pager(all, 20),
                o -> o.pricePerUnit().compareTo(new BigDecimal("10")) <= 0);
        assertEquals(21, p.matchable(), "musi pokryć 21 (nie zatrzymać się na 20)");
        assertEquals(new BigDecimal("105"), p.totalMoney(), "21 x 5 = 105");
    }

    @Test
    void coversExactlyTwentyOnLastPageBoundary() {
        List<BazaarOrder> all = new ArrayList<>();
        for (int i = 1; i <= 20; i++) all.add(sell(i, 1, "5"));
        MatchPreview p = BazaarOrderService.scanPreview(20, 20, pager(all, 20),
                o -> o.pricePerUnit().compareTo(new BigDecimal("10")) <= 0);
        assertEquals(20, p.matchable());
    }

    @Test
    void coversManyFragmentedOffersAcrossPages() {
        List<BazaarOrder> all = new ArrayList<>();
        for (int i = 1; i <= 250; i++) all.add(sell(i, 1, "2"));   // 250 ofert po 1 szt.
        MatchPreview p = BazaarOrderService.scanPreview(250, 100, pager(all, 100),
                o -> o.pricePerUnit().compareTo(new BigDecimal("10")) <= 0);
        assertEquals(250, p.matchable(), "skan przez wiele stron");
        assertEquals(new BigDecimal("500"), p.totalMoney());
    }

    @Test
    void stopsAtPriceLimitAcrossPages() {
        // pierwsze 25 tanich, potem droga oferta -> skan zatrzymuje się na limicie ceny.
        List<BazaarOrder> all = new ArrayList<>();
        for (int i = 1; i <= 25; i++) all.add(sell(i, 1, "5"));
        all.add(sell(26, 100, "999"));                            // powyżej limitu
        MatchPreview p = BazaarOrderService.scanPreview(100, 20, pager(all, 20),
                o -> o.pricePerUnit().compareTo(new BigDecimal("10")) <= 0);
        assertEquals(25, p.matchable(), "zatrzymanie na limicie ceny, nie NOT_ENOUGH");
        assertEquals(new BigDecimal("125"), p.totalMoney());
    }

    @Test
    void insufficientTotalReturnsPartialMatch() {
        List<BazaarOrder> all = new ArrayList<>();
        for (int i = 1; i <= 5; i++) all.add(sell(i, 1, "5"));
        MatchPreview p = BazaarOrderService.scanPreview(100, 20, pager(all, 20),
                o -> o.pricePerUnit().compareTo(new BigDecimal("10")) <= 0);
        assertEquals(5, p.matchable(), "tylko 5 dostępnych -> matchable 5 (caller decyduje o NOT_ENOUGH)");
    }

    @Test
    void repositoryPageUsesLimitOffsetSql() {
        InMemoryDb db = new InMemoryDb();
        BazaarOrderRepository repo = new BazaarOrderRepository(db);
        repo.pageOpenSellOffers("diamond", 100, 200);
        List<InMemoryDb.Op> ops = db.operations();
        String sql = ops.get(ops.size() - 1).sql();
        assertTrue(sql.contains("LIMIT ? OFFSET ?"), "stronicowanie po stronie DB: " + sql);
        assertTrue(sql.contains("ORDER BY price_per_unit ASC, id ASC"), "kolejność dopasowania: " + sql);
    }
}
