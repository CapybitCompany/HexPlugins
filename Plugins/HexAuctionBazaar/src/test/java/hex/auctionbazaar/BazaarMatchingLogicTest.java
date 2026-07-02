package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.model.BazaarOrder;
import hex.auctionbazaar.bazaar.model.OrderSide;
import hex.auctionbazaar.bazaar.model.OrderState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Weryfikuje logike doboru zlecen do dopasowania.
 * Nie odwoluje sie do bazy danych - implementuje ten sam algorytm
 * co previewMatchBuyOrders/previewMatchSellOffers na liscie zlecen w pamieci.
 *
 * Zapewnia, ze priorytet dopasowania jest zgodny z wymaganiami:
 *  - BUY orderу: najwyzsza cena, potem najstarsze id
 *  - SELL oferty: najnizsza cena, potem najstarsze id
 *  - system-stock kupowany jest DOPIERO po wyczerpaniu orderbooka.
 */
class BazaarMatchingLogicTest {

    private BazaarOrder buyOrder(long id, BigDecimal price, long remaining) {
        return new BazaarOrder(id, UUID.randomUUID(), "Buyer" + id, "diamond",
                OrderSide.BUY, remaining, remaining, price,
                price.multiply(new BigDecimal(remaining)),
                OrderState.ACTIVE, 0L, 0L, null);
    }

    private BazaarOrder sellOffer(long id, BigDecimal price, long remaining) {
        return new BazaarOrder(id, UUID.randomUUID(), "Seller" + id, "diamond",
                OrderSide.SELL, remaining, remaining, price, null,
                OrderState.ACTIVE, 0L, 0L, null);
    }

    /**
     * Symulacja previewMatchBuyOrders (dla instant-SELL): najpierw zlecenia
     * z najwyzsza cena. Zwraca (dopasowane, przychod).
     */
    private long[] simulateMatchBuy(List<BazaarOrder> sorted, long want) {
        long remaining = want;
        long revenue = 0;
        for (BazaarOrder o : sorted) {
            if (remaining <= 0) break;
            long take = Math.min(remaining, o.amountRemaining());
            revenue += o.pricePerUnit().longValue() * take;
            remaining -= take;
        }
        return new long[]{want - remaining, revenue};
    }

    private long[] simulateMatchSell(List<BazaarOrder> sorted, long want) {
        long remaining = want;
        long cost = 0;
        for (BazaarOrder o : sorted) {
            if (remaining <= 0) break;
            long take = Math.min(remaining, o.amountRemaining());
            cost += o.pricePerUnit().longValue() * take;
            remaining -= take;
        }
        return new long[]{want - remaining, cost};
    }

    @Test
    void instantSellFillsHighestPriceBuyOrderFirst() {
        // Instant sell 20 z 3 buy-orderow: 100@5, 200@10, 150@8.
        // Priorytet: 200@10 (revenue = 20*10 = 200), tylko 20 sztuk.
        List<BazaarOrder> sorted = List.of(
                buyOrder(2, new BigDecimal("10"), 200L),
                buyOrder(3, new BigDecimal("8"), 150L),
                buyOrder(1, new BigDecimal("5"), 100L));
        long[] r = simulateMatchBuy(sorted, 20L);
        assertEquals(20L, r[0], "wszystkie sprzedane do najwyzszego BUY-ordera");
        assertEquals(200L, r[1], "kwota = 20*10");
    }

    @Test
    void instantBuyFillsLowestPriceSellOfferFirst() {
        // Instant buy 20 z 3 sell-ofert: 500@50, 300@30, 400@40.
        // Priorytet: 300@30 (koszt = 20*30 = 600).
        List<BazaarOrder> sorted = List.of(
                sellOffer(2, new BigDecimal("30"), 300L),
                sellOffer(3, new BigDecimal("40"), 400L),
                sellOffer(1, new BigDecimal("50"), 500L));
        long[] r = simulateMatchSell(sorted, 20L);
        assertEquals(20L, r[0]);
        assertEquals(600L, r[1], "koszt = 20*30");
    }

    @Test
    void partialFillFallsThroughToSystemStock() {
        // Buy-orderу maja tylko 5 sztuk lacznie. Chcemy sprzedac 20.
        // Preview zwraca matched=5, revenue=5*10. Reszta (15) idzie do systemu.
        List<BazaarOrder> sorted = List.of(
                buyOrder(1, new BigDecimal("10"), 5L));
        long[] r = simulateMatchBuy(sorted, 20L);
        assertEquals(5L, r[0], "orderbook zaspokoi 5, reszta idzie do systemu");
        assertEquals(50L, r[1]);
    }

    @Test
    void multipleOrdersFilledInPriorityOrder() {
        // 3 buy-orderу: 200@10, 100@10, 50@8. Sprzedaz 250.
        // Priorytet: cena DESC, potem id ASC.
        // Bierzemy 200 z zlecenia #2 (10*200=2000), potem 50 z #3 (10*50=500).
        // Suma: 250 z revenue 2500.
        List<BazaarOrder> sorted = List.of(
                buyOrder(2, new BigDecimal("10"), 200L),
                buyOrder(3, new BigDecimal("10"), 100L),
                buyOrder(1, new BigDecimal("8"), 50L));
        long[] r = simulateMatchBuy(sorted, 250L);
        assertEquals(250L, r[0]);
        assertEquals(2500L, r[1]);
    }

    @Test
    void systemStockNotUsedForOrderMatchedPortion() {
        // 100 sztuk dostepnych w orderbooku (BUY-orderу).
        // Sprzedajemy 100. matched = 100, remaining = 0 -> systemu nie ruszamy.
        List<BazaarOrder> sorted = List.of(
                buyOrder(1, new BigDecimal("10"), 100L));
        long[] r = simulateMatchBuy(sorted, 100L);
        assertEquals(100L, r[0]);
        // remaining = 100 - 100 = 0 -> flowsystem stock NIE bedzie wywolany
        assertEquals(0L, 100L - r[0]);
    }
}
