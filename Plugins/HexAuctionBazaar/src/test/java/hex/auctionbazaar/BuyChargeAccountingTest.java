package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.service.BazaarService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresja bledu #1: instant BUY undercharges / gives free system-stock items
 * po race'ie w orderbooku.
 *
 * BazaarService.computeFinalBuyCharge to pure function ograniczajaca ilosc
 * dostarczanych z magazynu przedmiotow do kredytu, jaki gracz zaplacil z gory.
 * Niezmiennik: nigdy nie dostarcza wiecej niz gracz zaplacil.
 */
class BuyChargeAccountingTest {

    @Test
    void previewFullMatchNoSystemFallback() {
        // Pre-scan: orderCost=1000, systemCost=0 -> total 1000.
        // Match: actualOrderCost=1000, matched=100. shortfall=0.
        var acc = BazaarService.computeFinalBuyCharge(
                new BigDecimal("1000"), new BigDecimal("1000"),
                0, 500L, new BigDecimal("15"));
        assertEquals(0, acc.systemFill());
        assertEquals(BigDecimal.ZERO, acc.systemCost());
        assertEquals(new BigDecimal("1000"), acc.finalCharge());
        assertEquals(BigDecimal.ZERO, acc.refund());
    }

    @Test
    void partialMatchFallsThroughToSystemAtCorrectCost() {
        // Pre-scan: matchable=10 @ 20 = 200, systemNeeded=5 @ 15 = 75. total=275.
        // Match: actualMatched=6, actualOrderCost=120.
        // shortfall = 10+5-6 = 9. availableCredit = 275-120 = 155.
        // maxAffordable = 155 / 15 = 10 (floor). cappedByStock = min(9, 500) = 9.
        // systemFill = min(9, 10) = 9. systemCost = 9*15 = 135.
        // finalCharge = 120 + 135 = 255. refund = 275 - 255 = 20.
        var acc = BazaarService.computeFinalBuyCharge(
                new BigDecimal("275"), new BigDecimal("120"),
                9, 500L, new BigDecimal("15"));
        assertEquals(9, acc.systemFill());
        assertEquals(new BigDecimal("135"), acc.systemCost());
        assertEquals(new BigDecimal("255"), acc.finalCharge());
        assertEquals(new BigDecimal("20"), acc.refund());
        assertTrue(acc.finalCharge().compareTo(new BigDecimal("275")) <= 0,
                "finalCharge nigdy nie moze byc wieksze niz totalWithdrawn");
    }

    @Test
    void systemFillNeverExceedsCredit() {
        // Skrajny test: pre-scan zaplanowal 5 z systemu, ale race calkowicie
        // wyczyscil orderbook. shortfall = matchable + systemNeeded - 0.
        // Pre-scan: matchable=10 @ 20 = 200, systemNeeded=5 @ 15 = 75. total=275.
        // actualMatched=0, actualOrderCost=0. shortfall=15.
        // availableCredit=275, maxAffordable = 275/15 = 18 (floor).
        // cappedByStock = min(15, 500) = 15. systemFill = 15. cost = 225.
        // Wait: totalWithdrawn=275, but finalCharge=225 -> refund=50. OK.
        //
        // Ale gracz zaplacil 275 za planowo 15 sztuk (10 z orderbooka +5 z systemu).
        // Teraz dostaje 15 z systemu za 225 -> zwrot 50. Nie wiecej niz zamowione.
        var acc = BazaarService.computeFinalBuyCharge(
                new BigDecimal("275"), BigDecimal.ZERO,
                15, 500L, new BigDecimal("15"));
        assertEquals(15, acc.systemFill());
        assertEquals(new BigDecimal("225"), acc.systemCost());
        assertEquals(new BigDecimal("225"), acc.finalCharge());
        assertEquals(new BigDecimal("50"), acc.refund());
    }

    @Test
    void systemPriceInflationCannotDeliverMoreThanPaid() {
        // Race spowodowal, ze order match dal MNIEJ niz oczekiwano, i cena
        // systemowa jest wysoka. Nie wolno dostarczyc gracza wiecej niz kredyt.
        // Pre-scan: matchable=10 @ 10 = 100, systemNeeded=0. total=100.
        // Match: actualMatched=0. shortfall=10. Ale cena system=20.
        // availableCredit=100. maxAffordable=100/20=5. systemFill=5. cost=100.
        // Player pays exactly 100 for 5 items. Refund 0.
        var acc = BazaarService.computeFinalBuyCharge(
                new BigDecimal("100"), BigDecimal.ZERO,
                10, 500L, new BigDecimal("20"));
        assertEquals(5, acc.systemFill(),
                "kredyt 100 przy cenie 20 pokrywa tylko 5 sztuk");
        assertEquals(new BigDecimal("100"), acc.systemCost());
        assertEquals(BigDecimal.ZERO, acc.refund());
    }

    @Test
    void stockLimitAppliedFirst() {
        // Pre-scan: matchable=5, systemNeeded=10, total credit ok, ale
        // stock=3 wiec z systemu bierzemy tylko 3.
        var acc = BazaarService.computeFinalBuyCharge(
                new BigDecimal("200"), new BigDecimal("50"),
                10, 3L, new BigDecimal("15"));
        assertEquals(3, acc.systemFill());
        assertEquals(new BigDecimal("45"), acc.systemCost());
        assertEquals(new BigDecimal("95"), acc.finalCharge());
        assertEquals(new BigDecimal("105"), acc.refund());
    }

    @Test
    void zeroSystemPriceGrantsAllRequestedFromStock() {
        // Legalny scenariusz: system price = 0. Kredyt >= 0 wystarczy.
        var acc = BazaarService.computeFinalBuyCharge(
                new BigDecimal("100"), new BigDecimal("100"),
                5, 500L, BigDecimal.ZERO);
        assertEquals(5, acc.systemFill());
        assertEquals(BigDecimal.ZERO, acc.systemCost());
        assertEquals(new BigDecimal("100"), acc.finalCharge());
        assertEquals(BigDecimal.ZERO, acc.refund());
    }

    @Test
    void nullSystemPriceTreatedAsZero() {
        var acc = BazaarService.computeFinalBuyCharge(
                new BigDecimal("100"), new BigDecimal("100"),
                3, 500L, null);
        assertEquals(3, acc.systemFill());
        assertEquals(BigDecimal.ZERO, acc.systemCost());
    }

    @Test
    void refundNeverNegative() {
        var acc = BazaarService.computeFinalBuyCharge(
                new BigDecimal("100"), new BigDecimal("50"),
                20, 500L, new BigDecimal("10"));
        // availableCredit=50, maxAffordable=5, systemFill=5, cost=50, finalCharge=100
        assertEquals(5, acc.systemFill());
        assertEquals(BigDecimal.ZERO, acc.refund());
        assertTrue(acc.refund().signum() >= 0);
    }

    @Test
    void noSystemDeliveryWhenCreditExhaustedByOrderbook() {
        // Actual order cost consumed all credit -> nothing left for system.
        var acc = BazaarService.computeFinalBuyCharge(
                new BigDecimal("100"), new BigDecimal("100"),
                5, 500L, new BigDecimal("10"));
        assertEquals(0, acc.systemFill(), "brak kredytu -> zero z systemu");
        assertEquals(BigDecimal.ZERO, acc.systemCost());
        assertEquals(new BigDecimal("100"), acc.finalCharge());
    }
}
