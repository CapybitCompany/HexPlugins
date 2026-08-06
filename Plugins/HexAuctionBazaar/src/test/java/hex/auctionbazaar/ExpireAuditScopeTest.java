package hex.auctionbazaar;

import hex.auctionbazaar.auction.model.AuctionListing;
import hex.auctionbazaar.auction.model.ListingState;
import hex.auctionbazaar.auction.repository.AuctionListingRepository;
import hex.auctionbazaar.testutil.InMemoryDb;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #10: {@code expireBatchWithClaimsTx} MUSI zwracać tylko aukcje faktycznie przestawione
 * na EXPIRED (UPDATE trafił 1 wiersz w stanie ACTIVE). Aukcja w międzyczasie kupiona/anulowana
 * (UPDATE = 0 wierszy) NIE może trafić do wyniku - dzięki temu caller nie audytuje jej jako EXPIRED.
 */
class ExpireAuditScopeTest {

    private static AuctionListing listing(long id, UUID seller) {
        return new AuctionListing(id, seller, "Seller", new byte[]{1}, "DIAMOND", 1,
                new BigDecimal("100"), ListingState.ACTIVE, 0L, Long.MAX_VALUE, null, null, null, null,
                null, null, null, null);
    }

    @Test
    void onlyActuallyExpiredListingsAreReturned() {
        InMemoryDb db = new InMemoryDb();
        // stan-UPDATE ma params [EXPIRED, id, ACTIVE]; symulujemy, że id=2 nie jest już ACTIVE (0 wierszy).
        db.setDefaultUpdate(params -> {
            if (!params.isEmpty() && ListingState.EXPIRED.name().equals(params.get(0))) {
                return Long.valueOf(2L).equals(params.get(1)) ? 0 : 1;
            }
            return 1;   // insert claimu itp.
        });
        AuctionListingRepository repo = new AuctionListingRepository(db);
        UUID seller = UUID.randomUUID();

        List<AuctionListing> expired = repo.expireBatchWithClaimsTx(
                List.of(listing(1L, seller), listing(2L, seller)), 123L);

        assertEquals(1, expired.size(), "tylko realnie wygasłe (id=1); id=2 kupione w międzyczasie");
        assertEquals(1L, expired.get(0).id());
    }

    @Test
    void emptyInputReturnsEmpty() {
        AuctionListingRepository repo = new AuctionListingRepository(new InMemoryDb());
        assertTrue(repo.expireBatchWithClaimsTx(List.of(), 1L).isEmpty());
    }
}
