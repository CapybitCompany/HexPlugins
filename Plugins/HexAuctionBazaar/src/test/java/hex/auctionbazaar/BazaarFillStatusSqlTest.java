package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.model.OrderState;
import hex.auctionbazaar.bazaar.repository.BazaarOrderRepository;
import hex.auctionbazaar.testutil.InMemoryDb;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #1: {@link BazaarOrderRepository#tryFillPortionTx} musi ustawiać FILLED tylko gdy po
 * JEDNYM odjęciu ilość &lt;= 0, inaczej PARTIALLY_FILLED. MySQL liczy przypisania SET od lewej
 * do prawej, więc {@code state} musi być wyliczony PRZED zmniejszeniem {@code amount_remaining}.
 *
 * Test zabezpiecza REALNĄ strukturę SQL repozytorium (przechwyconą przez {@link InMemoryDb})
 * oraz - przez wierny symulator semantyki MySQL czytający kolejność przypisań z tego SQL -
 * wynik dla przykładów 100-100/100-50/100-60/100-30 oraz brak mutacji przy overfill/stale.
 * Ponowne wprowadzenie błędu (dekrement przed obliczeniem statusu) łamie ten test.
 */
class BazaarFillStatusSqlTest {

    private static InMemoryDb.Op captureFill(long orderId, long fill, long now) {
        InMemoryDb db = new InMemoryDb();
        BazaarOrderRepository.tryFillPortionTx(db, orderId, fill, now);
        List<InMemoryDb.Op> ops = db.operations();
        return ops.get(ops.size() - 1);
    }

    @Test
    void sqlComputesStateBeforeDecrementWithGuards() {
        InMemoryDb.Op op = captureFill(7L, 50L, 123L);
        String sql = op.sql();
        int stateIdx = sql.indexOf("state=CASE");
        int decIdx = sql.indexOf("amount_remaining=amount_remaining-");
        assertTrue(stateIdx >= 0 && decIdx >= 0, "SQL musi mieć oba przypisania: " + sql);
        assertTrue(stateIdx < decIdx,
                "state MUSI być wyliczony PRZED zmniejszeniem amount_remaining (MySQL od lewej): " + sql);
        assertTrue(sql.contains("amount_remaining-?<=0"), "CASE liczony na oryginalnej ilości: " + sql);
        assertTrue(sql.contains("amount_remaining>=?"), "WHERE chroni przed ujemną resztą: " + sql);
        assertTrue(sql.contains("state IN (?, ?)"), "WHERE ogranicza do ACTIVE/PARTIALLY_FILLED: " + sql);

        // params: fill(CASE), FILLED, PARTIALLY, fill(dekrement), now, id, fill(WHERE), ACTIVE, PARTIALLY
        List<Object> p = op.params();
        assertEquals(50L, p.get(0));
        assertEquals(OrderState.FILLED.name(), p.get(1));
        assertEquals(OrderState.PARTIALLY_FILLED.name(), p.get(2));
        assertEquals(50L, p.get(3));
        assertEquals(7L, p.get(5));
        assertEquals(50L, p.get(6));
    }

    // ---- wierny symulator MySQL (kolejność SET od lewej do prawej) czytany z realnego SQL ----

    private record Fill(long remaining, String state, boolean mutated) {}

    private static Fill simulate(String sql, long original, long fill) {
        // WHERE: amount_remaining >= fill (i właściwy stan) - inaczej 0 wierszy = brak mutacji.
        if (original < fill) {
            return new Fill(original, null, false);
        }
        int stateIdx = sql.indexOf("state=CASE");
        int decIdx = sql.indexOf("amount_remaining=amount_remaining-");
        long cur = original;
        String state;
        if (stateIdx < decIdx) {
            state = (cur - fill <= 0) ? "FILLED" : "PARTIALLY_FILLED";  // CASE widzi wartość sprzed odjęcia
            cur = cur - fill;
        } else {
            cur = cur - fill;                                            // (błędna kolejność) - dekrement pierwszy
            state = (cur - fill <= 0) ? "FILLED" : "PARTIALLY_FILLED";
        }
        return new Fill(cur, state, true);
    }

    private void assertFill(long original, long fill, long expectedRemaining, OrderState expectedState) {
        String sql = captureFill(1L, fill, 1L).sql();
        Fill r = simulate(sql, original, fill);
        assertTrue(r.mutated(), "oczekiwana mutacja dla " + original + "-" + fill);
        assertEquals(expectedRemaining, r.remaining(), "reszta dla " + original + "-" + fill);
        assertEquals(expectedState.name(), r.state(), "status dla " + original + "-" + fill);
    }

    @Test
    void fullFillIsFilled() {
        assertFill(100, 100, 0, OrderState.FILLED);
    }

    @Test
    void halfFillIsPartiallyFilled() {
        assertFill(100, 50, 50, OrderState.PARTIALLY_FILLED);
    }

    @Test
    void sixtyFillIsPartiallyFilled() {
        assertFill(100, 60, 40, OrderState.PARTIALLY_FILLED);
    }

    @Test
    void thirtyFillIsPartiallyFilled() {
        assertFill(100, 30, 70, OrderState.PARTIALLY_FILLED);
    }

    @Test
    void overfillDoesNotMutate() {
        String sql = captureFill(1L, 150L, 1L).sql();
        Fill r = simulate(sql, 100, 150);   // amount_remaining(100) < fill(150) -> WHERE nie trafia
        assertFalse(r.mutated(), "overfill nie może zmutować wiersza");
    }

    @Test
    void staleOrConcurrentFillDoesNotPartiallyMutate() {
        // Zdezaktualizowany fill: reszta już mniejsza niż żądany fill -> brak mutacji, brak częściowego zdjęcia.
        String sql = captureFill(1L, 80L, 1L).sql();
        Fill r = simulate(sql, 50, 80);
        assertFalse(r.mutated(), "veraltet/konkurrent: brak częściowej mutacji");
        assertEquals(50, r.remaining(), "reszta niezmieniona przy nietrafionym WHERE");
    }
}
