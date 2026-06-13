package hex.auctionbazaar;

import hex.auctionbazaar.auction.model.ListingState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AuctionListingStateTest {

    @Test
    void allStatesExist() {
        // Sanity: state machine surface unchanged.
        assertNotNull(ListingState.valueOf("ACTIVE"));
        assertNotNull(ListingState.valueOf("RESERVED"));
        assertNotNull(ListingState.valueOf("SOLD"));
        assertNotNull(ListingState.valueOf("CANCELLED"));
        assertNotNull(ListingState.valueOf("EXPIRED"));
        assertEquals(5, ListingState.values().length);
    }
}
