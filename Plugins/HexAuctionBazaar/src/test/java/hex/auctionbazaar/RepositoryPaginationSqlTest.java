package hex.auctionbazaar;

import hex.auctionbazaar.auction.repository.AuctionClaimRepository;
import hex.auctionbazaar.auction.repository.AuctionListingRepository;
import hex.auctionbazaar.testutil.InMemoryDb;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #7: stronicowanie po stronie DB (LIMIT/OFFSET), nie w Javie.
 * Weryfikujemy, że repo faktycznie wysyła LIMIT ? OFFSET ? oraz osobny COUNT.
 */
class RepositoryPaginationSqlTest {

    @Test
    void myListingsUsesLimitOffset() {
        InMemoryDb db = new InMemoryDb();
        AuctionListingRepository repo = new AuctionListingRepository(db);
        UUID seller = UUID.randomUUID();

        repo.findActiveBySeller(seller, 45, 90);
        InMemoryDb.Op op = last(db);
        assertTrue(op.sql().contains("LIMIT ? OFFSET ?"), "musi używać LIMIT/OFFSET: " + op.sql());
        // params: seller, ACTIVE, RESERVED, limit, offset
        assertEquals(45, op.params().get(3));
        assertEquals(90, op.params().get(4));
    }

    @Test
    void claimsUseLimitOffsetAndSeparateCount() {
        InMemoryDb db = new InMemoryDb();
        AuctionClaimRepository repo = new AuctionClaimRepository(db);
        UUID owner = UUID.randomUUID();

        repo.findPendingByOwner(owner, 45, 45);
        InMemoryDb.Op paged = last(db);
        assertTrue(paged.sql().contains("LIMIT ? OFFSET ?"), "claims muszą używać LIMIT/OFFSET");
        assertEquals(45, paged.params().get(2));
        assertEquals(45, paged.params().get(3));

        repo.countPendingByOwner(owner);
        InMemoryDb.Op count = last(db);
        assertTrue(count.sql().contains("COUNT(*)"), "osobny COUNT dla stron");
        assertTrue(count.sql().contains("state=?"));
    }

    private static InMemoryDb.Op last(InMemoryDb db) {
        List<InMemoryDb.Op> ops = db.operations();
        return ops.get(ops.size() - 1);
    }
}
