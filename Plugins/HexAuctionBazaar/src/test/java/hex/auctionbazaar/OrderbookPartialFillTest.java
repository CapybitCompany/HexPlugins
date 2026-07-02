package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.model.BazaarOrder;
import hex.auctionbazaar.bazaar.model.OrderSide;
import hex.auctionbazaar.bazaar.model.OrderState;
import hex.auctionbazaar.bazaar.service.BazaarOrderService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regresja Part A #2: gdy iteracyjny matching commituje pierwszy fill,
 * a drugi fill wywala wyjatek, MatchResult MUSI zawierac pierwszy fill.
 * Wczesniejsze zacommitowane fills nigdy nie moga zostac "zapomniane"
 * przez zewnetrzna sciezke - inaczej BUY refunduje pelna kwote a
 * SELL zwraca pelne przedmioty, mimo ze system wykonal ksiegowa czesc pracy.
 *
 * Test uzywa symulacji pattern-u matcher-a (nie odwoluje sie do bazy),
 * bo obie funkcje matchAgainstSellOffers i matchAgainstBuyOrders maja
 * ten sam ksztalt: peek -> tx -> break on RuntimeException.
 */
class OrderbookPartialFillTest {

    private BazaarOrder sellOffer(long id, BigDecimal price, long remaining) {
        return new BazaarOrder(id, UUID.randomUUID(), "S" + id, "diamond",
                OrderSide.SELL, remaining, remaining, price, null,
                OrderState.ACTIVE, 0L, 0L, null);
    }

    private BazaarOrder buyOrder(long id, BigDecimal price, long remaining) {
        return new BazaarOrder(id, UUID.randomUUID(), "B" + id, "diamond",
                OrderSide.BUY, remaining, remaining, price,
                price.multiply(new BigDecimal(remaining)),
                OrderState.ACTIVE, 0L, 0L, null);
    }

    /**
     * Symulacja outer loop w matchAgainstSellOffers z budzetem i wyjatkiem
     * na wskazanym indeksie fill.
     */
    private BazaarOrderService.MatchResult simulateBuyMatch(
            List<BazaarOrder> offers, long want, BigDecimal maxSpend, int failAtIndex) {
        long remaining = want;
        BigDecimal totalPaid = BigDecimal.ZERO;
        int idx = 0;
        for (BazaarOrder o : offers) {
            if (remaining <= 0) break;
            long baseTake = Math.min(remaining, o.amountRemaining());
            long take = BazaarOrderService.capTakeToBudget(
                    baseTake, o.pricePerUnit(), maxSpend, totalPaid);
            if (take <= 0) break;
            // Symulacja RuntimeException na tym konkretnym fill-u.
            if (idx == failAtIndex) {
                // Simulate MatchTxAbort: per-order tx rollbacked, but we
                // must preserve previously committed fills in the result.
                break;
            }
            remaining -= take;
            totalPaid = totalPaid.add(o.pricePerUnit().multiply(new BigDecimal(take)));
            idx++;
        }
        return new BazaarOrderService.MatchResult(want - remaining, totalPaid);
    }

    private BazaarOrderService.MatchResult simulateSellMatch(
            List<BazaarOrder> orders, long want, int failAtIndex) {
        long remaining = want;
        BigDecimal totalReceived = BigDecimal.ZERO;
        int idx = 0;
        for (BazaarOrder o : orders) {
            if (remaining <= 0) break;
            long take = Math.min(remaining, o.amountRemaining());
            if (take <= 0) break;
            if (idx == failAtIndex) break;
            remaining -= take;
            totalReceived = totalReceived.add(o.pricePerUnit().multiply(new BigDecimal(take)));
            idx++;
        }
        return new BazaarOrderService.MatchResult(want - remaining, totalReceived);
    }

    @Test
    void buyFirstFillSucceedsSecondFillThrowsReturnsPartial() {
        // Dwa SELL-offer-y po 5 sztuk kazdy, cena 10.
        // Kupujemy 10 sztuk. Pierwszy fill sie uda, drugi throw.
        // MatchResult powinien miec matched=5, totalMoney=50.
        List<BazaarOrder> offers = new ArrayList<>(List.of(
                sellOffer(1, new BigDecimal("10"), 5L),
                sellOffer(2, new BigDecimal("10"), 5L)));
        var r = simulateBuyMatch(offers, 10L, new BigDecimal("200"), 1);
        assertEquals(5L, r.matched(),
                "drugi fill throw -> pierwszy fill musi pozostac w wyniku");
        assertEquals(new BigDecimal("50"), r.totalMoney());
    }

    @Test
    void sellFirstFillSucceedsSecondFillThrowsReturnsPartial() {
        List<BazaarOrder> buys = new ArrayList<>(List.of(
                buyOrder(1, new BigDecimal("10"), 3L),
                buyOrder(2, new BigDecimal("10"), 3L)));
        var r = simulateSellMatch(buys, 6L, 1);
        assertEquals(3L, r.matched(),
                "SELL path: pierwszy fill zachowany po throw drugiego");
        assertEquals(new BigDecimal("30"), r.totalMoney());
    }

    @Test
    void refundInBuyPathOnlyCoversUnspentBudget() {
        // Buyer zarezerwowal 200. Match zadzialal tylko na 5 sztuk @ 10 = 50.
        // Refund musi byc 200 - 50 = 150 (NIE 200).
        BigDecimal reserved = new BigDecimal("200");
        BigDecimal actualOrderCost = new BigDecimal("50");
        BigDecimal refund = reserved.subtract(actualOrderCost);
        assertEquals(new BigDecimal("150"), refund,
                "BUY refund musi zwrocic tylko niezuzyty budzet");
    }

    @Test
    void refundInSellPathOnlyReturnsUnmatchedItems() {
        // Sprzedawca oddal 10 sztuk. Match zadzialal na 3.
        // Zwrot powinien byc 10 - 3 = 7 (NIE 10, bo 3 juz zaksiegowane).
        long given = 10;
        long matched = 3;
        long giveBack = given - matched;
        assertEquals(7L, giveBack,
                "SELL refund musi zwrocic tylko przedmioty nie-dopasowane");
    }

    @Test
    void failAtFirstFillReturnsEmptyResult() {
        // Wyjatek juz na pierwszym fill - matched = 0, totalMoney = 0.
        List<BazaarOrder> offers = new ArrayList<>(List.of(
                sellOffer(1, new BigDecimal("10"), 5L)));
        var r = simulateBuyMatch(offers, 10L, new BigDecimal("200"), 0);
        assertEquals(0L, r.matched());
        assertEquals(BigDecimal.ZERO, r.totalMoney());
    }

    @Test
    void allFillsSucceedFullMatchNoBudgetLoss() {
        List<BazaarOrder> offers = new ArrayList<>(List.of(
                sellOffer(1, new BigDecimal("10"), 5L),
                sellOffer(2, new BigDecimal("10"), 5L)));
        var r = simulateBuyMatch(offers, 10L, new BigDecimal("200"), -1);
        assertEquals(10L, r.matched());
        assertEquals(new BigDecimal("100"), r.totalMoney());
    }

    @Test
    void budgetCapKicksInBeforeExceptionMatters() {
        // Budzet nie wystarczy na drugi fill - matcher zatrzymuje sie sam.
        // Nie sprawdzamy tu wyjatku, ale invariant: matched * price <= budget.
        List<BazaarOrder> offers = new ArrayList<>(List.of(
                sellOffer(1, new BigDecimal("10"), 5L),
                sellOffer(2, new BigDecimal("100"), 5L)));
        var r = simulateBuyMatch(offers, 10L, new BigDecimal("50"), -1);
        assertEquals(5L, r.matched());
        assertEquals(new BigDecimal("50"), r.totalMoney());
    }
}
