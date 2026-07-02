package hex.auctionbazaar;

import hex.auctionbazaar.testutil.InMemoryDb;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresja bledu #3: transaction rollback semantics.
 * Sprawdzamy, ze InMemoryDb (nasz podwojnik do testu) sluchawa Db.tx
 * kontraktu:
 *  - normalny return (nawet false) commituje.
 *  - throw RuntimeException wycofuje wszystkie operacje z transakcji.
 * Bo to jest EXACTLY the API contract wymuszany w kodzie produkcyjnym.
 */
class TxRollbackSemanticsTest {

    @Test
    void normalReturnCommitsOperations() {
        InMemoryDb db = new InMemoryDb();
        db.tx(tx -> {
            tx.update("UPDATE t SET x=1");
            tx.update("UPDATE t SET y=2");
            return true;
        });
        assertEquals(2, db.operations().size(),
                "normalny return musi commit-owac wszystkie ops");
    }

    @Test
    void normalReturnFalseAlsoCommitsOperations() {
        // Kluczowy pulapek: Db.tx commit-uje TAKZE gdy zwrocono false.
        InMemoryDb db = new InMemoryDb();
        db.tx(tx -> {
            tx.update("UPDATE t SET x=1");
            return false; // "false" != rollback
        });
        assertEquals(1, db.operations().size(),
                "return false rowniez commit-uje - to jest wlasnie powod, dla ktorego kod produkcyjny musi throw-ac");
    }

    @Test
    void thrownExceptionRollsBackOperations() {
        InMemoryDb db = new InMemoryDb();
        assertThrows(RuntimeException.class, () -> db.tx(tx -> {
            tx.update("UPDATE t SET x=1");
            tx.update("UPDATE t SET y=2");
            throw new RuntimeException("simulate consume failure");
        }));
        assertTrue(db.operations().isEmpty(),
                "throw wewnatrz tx musi wycofac wszystkie ops");
    }

    @Test
    void partialMutationRolledBackWhenSecondStepThrows() {
        // Symulujemy dokladny pattern z matchAgainstBuyOrders:
        // 1. tryFillPortionTx sie uda -> UPDATE zapisany.
        // 2. tryConsumeReservedMoneyTx nie moze (returns false) -> patternem: throw.
        // 3. Cala transakcja rolls back.
        InMemoryDb db = new InMemoryDb();
        db.setDefaultUpdate(params -> 1);

        assertThrows(RuntimeException.class, () -> db.tx(tx -> {
            int fillRows = tx.update("UPDATE orders SET amount=? WHERE id=?", 5, 42);
            if (fillRows != 1) return false;
            // Symulujemy niepowodzenie consume (updated=0)
            int consumeRows = 0;
            if (consumeRows != 1) {
                throw new IllegalStateException(
                        "reserved_money consume failed - throwing to rollback");
            }
            return true;
        }));
        assertTrue(db.operations().isEmpty(),
                "brak zapisanych zmian po rollback-u");
    }

    @Test
    void tryFillFalseBeforeMutationCommitsEmpty() {
        // Gdy tryFillPortionTx zwraca false (updated=0), zaden UPDATE nie
        // zostal zapisany. return false commit-uje pusto - poprawnie.
        InMemoryDb db = new InMemoryDb();
        db.setDefaultUpdate(params -> 0);

        Boolean r = db.tx(tx -> {
            int rows = tx.update("UPDATE orders SET amount=? WHERE id=?", 5, 42);
            if (rows != 1) return false;
            // Wewnetrzny consume nigdy nie osiagniety
            return true;
        });
        assertEquals(Boolean.FALSE, r);
        // Op zostal zapisany, bo update FIZYCZNIE zostal wywolany
        // (nasz double loguje wszystko), ale w prawdziwej DB nic sie nie zmienilo (updated=0).
        assertEquals(1, db.operations().size(),
                "update byl wywolany ale nic nie zmienil");
    }
}
