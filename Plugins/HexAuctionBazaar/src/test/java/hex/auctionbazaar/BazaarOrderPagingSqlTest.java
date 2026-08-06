package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.repository.BazaarOrderRepository;
import hex.auctionbazaar.testutil.InMemoryDb;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #8: GUI „Moje zlecenia" musi stronicować po stronie DB (LIMIT/OFFSET) z osobnym COUNT,
 * aby starsze zlecenia pozostały osiągalne. Test zabezpiecza realny SQL repozytorium.
 */
class BazaarOrderPagingSqlTest {

    private static InMemoryDb.Op last(InMemoryDb db) {
        List<InMemoryDb.Op> ops = db.operations();
        return ops.get(ops.size() - 1);
    }

    @Test
    void pageByOwnerUsesLimitOffset() {
        InMemoryDb db = new InMemoryDb();
        BazaarOrderRepository repo = new BazaarOrderRepository(db);
        repo.pageByOwner(UUID.randomUUID(), 45, 90);
        InMemoryDb.Op op = last(db);
        assertTrue(op.sql().contains("LIMIT ? OFFSET ?"), "stronicowanie DB: " + op.sql());
        assertTrue(op.sql().contains("ORDER BY id DESC"), "najnowsze pierwsze: " + op.sql());
        assertEquals(45, op.params().get(1));
        assertEquals(90, op.params().get(2));
    }

    @Test
    void countByOwnerUsesCountStar() {
        InMemoryDb db = new InMemoryDb();
        BazaarOrderRepository repo = new BazaarOrderRepository(db);
        repo.countByOwner(UUID.randomUUID());
        InMemoryDb.Op op = last(db);
        assertTrue(op.sql().contains("COUNT(*)"), "osobny COUNT do stron: " + op.sql());
        assertTrue(op.sql().contains("owner_uuid=?"));
    }
}
