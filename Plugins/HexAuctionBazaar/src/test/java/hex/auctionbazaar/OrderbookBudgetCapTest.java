package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.service.BazaarOrderService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regresja bledu Part A #1: instant BUY moze przeplacic w orderbooku,
 * jesli tansze pre-scan offer zniknely i pojawily sie drozsze.
 *
 * capTakeToBudget zabezpiecza, ze matchAgainstSellOffers nigdy nie utworzy
 * money-claim-ow dla SELL-owner-ow o sumie wyzszej niz zarezerwowany budzet
 * kupujacego (totalWithdrawn - actualSystemCost).
 */
class OrderbookBudgetCapTest {

    @Test
    void nullBudgetMeansNoCap() {
        long take = BazaarOrderService.capTakeToBudget(
                50, new BigDecimal("10"), null, BigDecimal.ZERO);
        assertEquals(50, take);
    }

    @Test
    void fullBudgetAvailableAllowsFullTake() {
        // 50 sztuk po 10 = 500, budzet 1000, alreadyPaid 0 -> caly baseTake.
        long take = BazaarOrderService.capTakeToBudget(
                50, new BigDecimal("10"),
                new BigDecimal("1000"), BigDecimal.ZERO);
        assertEquals(50, take);
    }

    @Test
    void exhaustedBudgetReturnsZero() {
        // alreadyPaid == maxSpend -> zaden przedmiot juz sie nie miesci.
        long take = BazaarOrderService.capTakeToBudget(
                50, new BigDecimal("10"),
                new BigDecimal("500"), new BigDecimal("500"));
        assertEquals(0, take, "brak budzetu -> break matching");
    }

    @Test
    void takeCappedToRemainingBudget() {
        // Budzet=1000, alreadyPaid=800, unitPrice=15.
        // remainingBudget=200, affordable = 200/15 = 13 (floor).
        // baseTake=50 -> cap na 13.
        long take = BazaarOrderService.capTakeToBudget(
                50, new BigDecimal("15"),
                new BigDecimal("1000"), new BigDecimal("800"));
        assertEquals(13, take);
        // Niezmiennik: 13 * 15 + 800 = 995 <= 1000.
    }

    @Test
    void expensiveOrderCannotSneakIn() {
        // Pre-scan preview: 100 sztuk @ 10 = 1000 (budget).
        // Ale sell offer zniknal i pojawil sie nowy @ 100 (10x drozszy).
        // Naiwna matching wykreowalby claim 100*100=10000 - grubo powyzej budzetu.
        // Cap: remainingBudget=1000, unitPrice=100, affordable=10.
        long take = BazaarOrderService.capTakeToBudget(
                100, new BigDecimal("100"),
                new BigDecimal("1000"), BigDecimal.ZERO);
        assertEquals(10, take, "budzet 1000 przy cenie 100 pozwala na 10 sztuk max");
    }

    @Test
    void zeroUnitPriceIsBudgetSafe() {
        // Ceny 0 nie wyczerpuja budzetu - moga wziac cala baseTake.
        long take = BazaarOrderService.capTakeToBudget(
                50, BigDecimal.ZERO,
                new BigDecimal("100"), new BigDecimal("50"));
        assertEquals(50, take);
    }

    @Test
    void mixedFillsCumulativeInvariant() {
        // Symulacja 3 kolejnych fills. Budzet 300.
        // 1) baseTake=10, unit=5 -> take=10 (koszt=50). alreadyPaid=50.
        long t1 = BazaarOrderService.capTakeToBudget(
                10, new BigDecimal("5"),
                new BigDecimal("300"), BigDecimal.ZERO);
        assertEquals(10, t1);
        BigDecimal paid1 = new BigDecimal("50");
        // 2) baseTake=20, unit=10 -> remaining=250, aff=25 -> take=20 (koszt=200). paid=250.
        long t2 = BazaarOrderService.capTakeToBudget(
                20, new BigDecimal("10"),
                new BigDecimal("300"), paid1);
        assertEquals(20, t2);
        BigDecimal paid2 = paid1.add(new BigDecimal("200"));
        // 3) baseTake=100, unit=30 -> remaining=50, aff=1 -> take=1 (koszt=30). paid=280.
        long t3 = BazaarOrderService.capTakeToBudget(
                100, new BigDecimal("30"),
                new BigDecimal("300"), paid2);
        assertEquals(1, t3);
    }

    @Test
    void negativeRemainingBudgetIsSafeZero() {
        // alreadyPaid nieznacznie przekroczylo budzet (nie powinno sie zdarzyc,
        // ale musi byc bezpieczne).
        long take = BazaarOrderService.capTakeToBudget(
                10, new BigDecimal("5"),
                new BigDecimal("100"), new BigDecimal("120"));
        assertEquals(0, take);
    }
}
